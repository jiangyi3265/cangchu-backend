package com.ruoyi.warehouse.carriers;

import com.ruoyi.warehouse.support.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.ruoyi.warehouse.carriers.DpdContract.*;

public class DpdClient {
    private final CarrierTransport.Client http;private final Map<String,Object> settings,credentials;private String accessToken;
    public DpdClient(CarrierTransport.Client http,Map<String,Object> settings,Map<String,Object> credentials){this.http=http;this.settings=DpdContract.settings(settings);this.credentials=DpdContract.credentials(credentials);}
    private Object call(String path,String method,Map<String,String> headers,Object body,boolean creation){
        var response=http.request(path,method,headers,body);int status=response.status();
        if(status!=200){boolean rejected=Set.of(401,403,429).contains(status);
            if(status==400&&response.body() instanceof Map<?,?> m&&m.get("error") instanceof List<?> errors&&!errors.isEmpty())rejected=errors.stream().allMatch(e->e instanceof Map<?,?> error&&Set.of("101","102","103").contains(String.valueOf(error.get("code"))));
            throw new CarrierFailure(status==401||status==403?"DPD_AUTH_DENIED":status==429?"DPD_RATE_LIMITED":rejected?"DPD_REQUEST_REJECTED":"DPD_REMOTE_ERROR",creation&&rejected);
        }return response.body();
    }
    private String token(){if(accessToken!=null)return accessToken;String basic=Base64.getEncoder().encodeToString((credentials.get("appKey")+":"+credentials.get("appSecret")).getBytes(StandardCharsets.UTF_8));
        var body=object(call("/v1/customer/auth/access","GET",Map.of("accept","application/json","authorization","Basic "+basic),null,false));Object raw=object(body.get("data")).get("accessToken");
        if(!(raw instanceof String value)||value.length()>16384||!value.matches("[-A-Za-z0-9_]+\\.[-A-Za-z0-9_]+\\.[-A-Za-z0-9_]+"))throw new CarrierFailure("DPD_AUTH_RESPONSE_INVALID");
        try{var claims=WarehouseJson.map(new String(Base64.getUrlDecoder().decode(value.split("\\.")[1]),StandardCharsets.UTF_8));long expiry=WarehouseJson.integer(claims.get("expiry"),"expiry",1,Long.MAX_VALUE/1000);long unit=WarehouseJson.integer(claims.get("dpd_business_id"),"business",1,2);if(unit!=("DPD".equals(settings.get("businessUnit"))?1:2))throw new CarrierFailure("DPD_ACCOUNT_MISMATCH");if(expiry<=Instant.now().getEpochSecond()+30)throw new CarrierFailure("DPD_AUTH_RESPONSE_INVALID");}catch(CarrierFailure e){throw e;}catch(Exception e){throw new CarrierFailure("DPD_AUTH_RESPONSE_INVALID");}
        return accessToken=value;
    }
    private Object authorized(String path,String method,Object body,boolean creation){return call(path,method,Map.of("accept","application/json","authorization","Bearer "+token(),"client-id",(String)credentials.get("appKey")),body,creation);}
    public List<Map<String,Object>> services(Map<String,Object> recipient,List<Map<String,Object>> parcels){
        var sender=object(settings.get("sender"));var body=Map.of("deliveryDetails",Map.of("address",serviceAddress(recipient)),"collectionDetails",Map.of("address",serviceAddress(sender)),"totalWeight",totalWeight(parcels),"numberOfParcels",parcels.size(),"shipmentType",0);
        Object raw=object(authorized("/v1/customer/shipping/reference/outboundservices","POST",body,false)).get("data");if(!(raw instanceof List<?> list)||list.size()>1000)throw new CarrierFailure("DPD_RESPONSE_INVALID");var result=new ArrayList<Map<String,Object>>();var codes=new HashSet<String>();
        for(Object row:list){var item=object(row);String code;try{code=text(item.get("networkKey"),"networkKey",255,false);}catch(Exception e){throw new CarrierFailure("DPD_RESPONSE_INVALID");}if(!codes.add(code))throw new CarrierFailure("DPD_RESPONSE_INVALID");result.add(item);}return result;
    }
    public List<Map<String,Object>> publicServices(Map<String,Object> recipient,List<Map<String,Object>> parcels){var result=new ArrayList<Map<String,Object>>();for(var service:services(recipient,parcels)){String reason="";try{requirements(service,recipient);}catch(CarrierFailure e){reason=e.code();}String name=service.get("networkDesc") instanceof String s&&s.length()<=255?s:"";result.add(Map.of("networkCode",service.get("networkKey"),"name",name,"supported",reason.isEmpty(),"reason",reason));}return result;}
    private static Map<String,Object> serviceAddress(Map<String,Object> a){return Map.of("countryCode","GB","town",a.get("town"),"postcode",a.get("postcode"),"county",a.get("county"));}
    static boolean requirements(Map<String,Object> service,Map<String,Object> recipient){
        var flags=Set.of("commodityRequired","costsRequired","exportReasonRequired","fdaRequired","pidRequired","prodRequired","proforma","proformaPrintRequired","skuRequired","taxRequired","urlRequired","eoriRequired","vatRequired","vatPaidRequired","exportImportRequired","busAtRiskRequired","ukimsRequired","contentsRequired","compositionRequired","productTypeRequired","countryOfOrigin");
        for(var e:service.entrySet())if(flags.contains(e.getKey())){if(!"N".equals(e.getValue())&&!"Y".equals(e.getValue()))throw new CarrierFailure("DPD_SERVICE_REQUIRES_SPECIAL_FLOW");}else if(e.getKey().endsWith("Required")&&!e.getKey().equals("notifyRequired")&&!"N".equals(e.getValue()))throw new CarrierFailure("DPD_SERVICE_REQUIRES_SPECIAL_FLOW");
        Object notify=service.get("notifyRequired");if(notify!=null&&!Set.of("N","Y","M").contains(notify))throw new CarrierFailure("DPD_RESPONSE_INVALID");if("M".equals(notify)&&(WarehouseJson.text(recipient,"email").isEmpty()||WarehouseJson.text(recipient,"mobile").isEmpty()))throw new CarrierFailure("DPD_NOTIFICATION_REQUIRED");
        if(service.get("networkDetails") instanceof Map<?,?> details&&details.containsKey("shipToPickup")&&!"N".equals(details.get("shipToPickup"))&&!"Y".equals(details.get("shipToPickup")))throw new CarrierFailure("DPD_SERVICE_REQUIRES_SPECIAL_FLOW");return "Y".equals(notify)||"M".equals(notify);
    }
    public Map<String,Object> prepare(String reference,String network,String shipDate,Map<String,Object> recipient,List<Map<String,Object>> parcels){
        text(reference,"reference",25,false);text(network,"networkCode",255,false);String date=shipmentDate(shipDate,Instant.now());var matches=services(recipient,parcels).stream().filter(s->network.equals(s.get("networkKey"))).toList();if(matches.size()!=1)throw new CarrierFailure("DPD_SERVICE_UNAVAILABLE");boolean notify=requirements(matches.get(0),recipient);
        var delivery=details(recipient);if(notify){var notification=new LinkedHashMap<String,Object>();for(String key:List.of("email","mobile"))if(!WarehouseJson.text(recipient,key).isEmpty())notification.put(key,recipient.get(key));if(!notification.isEmpty())delivery.put("notificationDetails",notification);}
        var consignment=new LinkedHashMap<String,Object>();consignment.put("deliveryDetails",delivery);consignment.put("collectionDetails",details(object(settings.get("sender"))));consignment.put("liability",false);consignment.put("totalWeight",totalWeight(parcels));consignment.put("numberOfParcels",parcels.size());consignment.put("networkCode",network);consignment.put("shippingRef1",reference);return Map.of("shipmentDate",date,"outboundConsignment",consignment);
    }
    private static Map<String,Object> details(Map<String,Object> address){var out=new LinkedHashMap<String,Object>();out.put("address",wireAddress(address));out.put("contactDetails",Map.of("contactName",address.get("name"),"telephone",address.get("phone")));return out;}
    public Map<String,Object> create(Map<String,Object> wire,int parcels){return identity(authorized("/v1/customer/shipping/shipments/domestic","POST",wire,true),parcels);}
    public String label(String shipment,String tracking){String query="?printerType=3&printerDpi="+settings.get("printerDpi")+"&parcelNumbers="+encode(opaque(tracking,14));return DpdContract.label(authorized("/v1/customer/shipping/shipments/"+encode(opaque(shipment,40))+"/labels"+query,"GET",null,false));}
    private static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20");}
}
