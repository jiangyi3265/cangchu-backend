package com.ruoyi.warehouse.carriers;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.ruoyi.warehouse.integrations.IntegrationTransport;
import com.ruoyi.warehouse.support.WarehouseJson;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.io.*;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class CarrierTransport {
    public record Response(int status,Object body,Map<String,String> headers){}
    @FunctionalInterface public interface Client {Response request(String path,String method,Map<String,String> headers,Object body);}
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(30).maxStringLength(2097152).maxNumberLength(80).build()).build()).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task->{var t=new Thread(task,"carrier-request-deadline");t.setDaemon(true);return t;});
    private final boolean enabled;private final String loopback;
    public CarrierTransport(@Value("${warehouse.carriers.enabled:false}")boolean enabled,@Value("${warehouse.carriers.loopback-test-origin:}")String origin,Environment env){
        if(!origin.isBlank()&&(!Arrays.asList(env.getActiveProfiles()).contains("warehouse-carrier-test")||!"127.0.0.1".equals(env.getProperty("server.address"))||!origin.matches("http://127\\.0\\.0\\.1:[1-9][0-9]{3,4}")))throw new IllegalStateException("Carrier loopback requires its explicit test profile and loopback server bind");
        this.enabled=enabled;loopback=origin;
    }
    public boolean enabled(){return enabled;}
    public boolean testMode(){return !loopback.isEmpty();}
    public void requireEnabled(){if(!enabled)throw new CarrierFailure("CARRIER_RUNTIME_DISABLED");}
    public Client client(String environment,Instant deadline){requireEnabled();String approved=DpdContract.origin(environment);String origin=testMode()?loopback:approved;return (path,method,headers,body)->request(origin,path,method,headers,body,deadline);}
    private Response request(String origin,String path,String method,Map<String,String> headers,Object body,Instant deadline){
        requireEnabled();if(!path.startsWith("/v1/customer/")||path.contains("#")||path.contains("\\")||path.contains("..")||path.chars().anyMatch(c->c<32||c==127)||!Set.of("GET","POST").contains(method))throw new CarrierFailure("CARRIER_PATH_INVALID");
        if(!Set.of(DpdContract.origin("sandbox"),DpdContract.origin("production"),loopback).contains(origin))throw new CarrierFailure("CARRIER_ORIGIN_INVALID");
        long remaining=Duration.between(Instant.now(),deadline).toMillis();if(remaining<=0)throw new CarrierFailure("CARRIER_TIME_BUDGET");URI uri=URI.create(origin+path);boolean local=testMode()&&origin.equals(loopback);
        DnsResolver dns=new DnsResolver(){public InetAddress[] resolve(String host)throws UnknownHostException{if(!host.equals(uri.getHost()))throw new UnknownHostException();var ips=InetAddress.getAllByName(host);for(var ip:ips)if(!(local&&ip.isLoopbackAddress())&&!IntegrationTransport.publicAddress(ip))throw new UnknownHostException();return ips;}public String resolveCanonicalHostname(String host){return host;}};
        var timeout=Timeout.ofMilliseconds(Math.min(8000,remaining));
        try(var manager=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(dns).setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build()).build();var client=HttpClients.custom().setConnectionManager(manager).setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(timeout).setConnectionRequestTimeout(timeout).build()).disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableContentCompression().build()){
            var request=new HttpUriRequestBase(method,uri);for(var h:headers.entrySet())request.setHeader(h.getKey(),h.getValue());request.setHeader("User-Agent","Warehouse-Carrier/1.0");if(body!=null)request.setEntity(new StringEntity(WarehouseJson.write(body),ContentType.APPLICATION_JSON));
            var cancel=DEADLINES.schedule(request::cancel,Math.max(1,Duration.between(Instant.now(),deadline).toMillis()),java.util.concurrent.TimeUnit.MILLISECONDS);
            try{return client.execute(request,response->{var retained=new HashMap<String,String>();var retry=response.getHeaders("Retry-After");if(retry.length>1)throw new CarrierFailure("DPD_RESPONSE_INVALID");if(retry.length==1)retained.put("retry-after",retry[0].getValue());
                if(response.getEntity()==null)return new Response(response.getCode(),Map.of(),retained);if(response.getEntity().getContentLength()>4194304)throw new CarrierFailure("CARRIER_RESPONSE_TOO_LARGE");byte[] bytes;try(var input=response.getEntity().getContent()){bytes=input.readNBytes(4194305);}if(bytes.length>4194304)throw new CarrierFailure("CARRIER_RESPONSE_TOO_LARGE");if(Instant.now().isAfter(deadline))throw new CarrierFailure("CARRIER_TIME_BUDGET");
                Object parsed;try{parsed=JSON.readValue(bytes,Object.class);}catch(Exception e){if(response.getCode()!=200)return new Response(response.getCode(),Map.of(),retained);throw new CarrierFailure("DPD_RESPONSE_INVALID");}return new Response(response.getCode(),parsed,retained);
            });}finally{cancel.cancel(false);}
        }catch(CarrierFailure e){throw e;}catch(Exception e){throw new CarrierFailure("CARRIER_CONNECTION_FAILED");}
    }
}
