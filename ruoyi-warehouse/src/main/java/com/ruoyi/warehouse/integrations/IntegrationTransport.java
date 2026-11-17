package com.ruoyi.warehouse.integrations;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.io.*;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class IntegrationTransport {
    public record Response(int status,Object body,Map<String,String> headers) { }
    @FunctionalInterface public interface Reader { Response get(String path,Map<String,String> query); }
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(30).maxStringLength(262144).maxNumberLength(80).build()).build()).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Set<String> hosts;private final String loopback;
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task->{var t=new Thread(task,"integration-read-deadline");t.setDaemon(true);return t;});
    public IntegrationTransport(@Value("${warehouse.integrations.allowed-hosts:}")String allowed,@Value("${warehouse.integrations.loopback-test-origin:}")String testOrigin,Environment env){
        hosts=new HashSet<>();for(String host:allowed.split(","))if(!host.isBlank())hosts.add(host.strip().toLowerCase(Locale.ROOT));
        if(!testOrigin.isBlank()&&(!Arrays.asList(env.getActiveProfiles()).contains("warehouse-integration-test")||!"127.0.0.1".equals(env.getProperty("server.address"))||!testOrigin.matches("http://127\\.0\\.0\\.1:[1-9][0-9]{3,4}")))throw new IllegalStateException("Integration loopback fixtures require the isolated test profile and loopback server binding");
        loopback=testOrigin;
    }
    public boolean testMode(){return !loopback.isEmpty();}
    public boolean allowed(String site){URI u=URI.create(site);return !loopback.isEmpty()&&(site.equals(loopback)||site.startsWith(loopback+"/"))||hosts.contains(u.getHost());}
    public String canonicalSite(String value){
        try{URI u=URI.create(value.strip());boolean local=!loopback.isEmpty()&&(value.strip().replaceAll("/+$","").equals(loopback)||value.strip().startsWith(loopback+"/"));String host=u.getHost();String path=u.getRawPath()==null?"":u.getRawPath();
            if(host==null||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||!local&&(!"https".equalsIgnoreCase(u.getScheme())||u.getPort()!=-1&&u.getPort()!=443)||!path.matches("(?:/[A-Za-z0-9_-]+)*/?")||path.toLowerCase(Locale.ROOT).contains("/wp-json")||value.length()>512)throw new IllegalArgumentException();
            return (local?loopback:"https://"+host.toLowerCase(Locale.ROOT))+path.replaceAll("/+$","");
        }catch(Exception e){throw new IntegrationFailure("STORE_URL_INVALID");}
    }
    public Reader reader(String site,Map<String,Object> credentials,Instant deadline){
        String canonical=canonicalSite(site);if(!allowed(canonical))throw new IntegrationFailure("STORE_HOST_NOT_ALLOWED");IntegrationVault.validate(credentials);
        String authorization="Basic "+Base64.getEncoder().encodeToString((credentials.get("consumerKey")+":"+credentials.get("consumerSecret")).getBytes(StandardCharsets.UTF_8));
        return (path,query)->get(canonical,path,query,authorization,deadline);
    }
    private Response get(String site,String path,Map<String,String> query,String authorization,Instant deadline){
        if(!path.matches("/(orders|products)(/[1-9][0-9]{0,18}(/(variations|fulfillments)(/[1-9][0-9]{0,18})?)?)?"))throw new IntegrationFailure("REMOTE_PATH_INVALID");
        long remaining=java.time.Duration.between(Instant.now(),deadline).toMillis();if(remaining<=0)throw new IntegrationFailure("READ_BUDGET_EXCEEDED");
        StringJoiner qs=new StringJoiner("&");query.forEach((k,v)->qs.add(URLEncoder.encode(k,StandardCharsets.UTF_8)+"="+URLEncoder.encode(v,StandardCharsets.UTF_8)));URI uri=URI.create(site+"/wp-json/wc/v3"+path+(qs.length()>0?"?"+qs:""));
        final boolean local=!loopback.isEmpty()&&(site.equals(loopback)||site.startsWith(loopback+"/"));
        DnsResolver resolver=new DnsResolver(){public InetAddress[] resolve(String host)throws UnknownHostException{if(!host.equals(uri.getHost()))throw new UnknownHostException();InetAddress[] addresses=InetAddress.getAllByName(host);for(InetAddress ip:addresses)if(!(local&&ip.isLoopbackAddress())&&!publicAddress(ip))throw new UnknownHostException();return addresses;}public String resolveCanonicalHostname(String host){return host;}};
        var timeout=Timeout.ofMilliseconds(Math.min(8000,remaining));
        try(var manager=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build()).build();var client=HttpClients.custom().setConnectionManager(manager).setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(timeout).setConnectionRequestTimeout(timeout).build()).disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableContentCompression().build()){
            var request=new HttpGet(uri);request.setHeader("Authorization",authorization);request.setHeader("Accept","application/json");request.setHeader("User-Agent","Warehouse-Integration/1.0");
            var cancellation=DEADLINES.schedule(request::cancel,Math.max(1,java.time.Duration.between(Instant.now(),deadline).toMillis()),java.util.concurrent.TimeUnit.MILLISECONDS);
            try{return client.execute(request,response->{var headers=new HashMap<String,String>();for(String name:List.of("X-WP-TotalPages","X-WP-Total","Retry-After")){var hs=response.getHeaders(name);if(hs.length>1)throw new IntegrationFailure("REMOTE_RESPONSE_INVALID");if(hs.length==1)headers.put(name.toLowerCase(Locale.ROOT),hs[0].getValue());}
                if(response.getEntity()==null)throw new IntegrationFailure("REMOTE_RESPONSE_INVALID");if(response.getEntity().getContentLength()>2097152)throw new IntegrationFailure("REMOTE_RESPONSE_TOO_LARGE");
                byte[] body;try(InputStream input=response.getEntity().getContent()){body=input.readNBytes(2097153);}if(body.length>2097152)throw new IntegrationFailure("REMOTE_RESPONSE_TOO_LARGE");
                if(Instant.now().isAfter(deadline))throw new IntegrationFailure("READ_BUDGET_EXCEEDED");
                Object parsed;try{parsed=JSON.readValue(body,Object.class);}catch(Exception e){if(response.getCode()>=300)return new Response(response.getCode(),Map.of(),Map.copyOf(headers));throw new IntegrationFailure("REMOTE_RESPONSE_INVALID");}return new Response(response.getCode(),parsed,Map.copyOf(headers));});}finally{cancellation.cancel(false);}
        }catch(IntegrationFailure e){throw e;}catch(Exception e){throw new IntegrationFailure("REMOTE_CONNECTION_FAILED");}
    }
    public static boolean publicAddress(InetAddress ip){
        if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress())return false;
        byte[] b=ip.getAddress();if(b.length==4){int a=b[0]&255,c=b[1]&255,d=b[2]&255;return a!=0&&a!=10&&a!=127&&a<224&&!(a==100&&c>=64&&c<=127)&&!(a==169&&c==254)&&!(a==172&&c>=16&&c<=31)&&!(a==192&&(c==168||c==0&&(d==0||d==2)||c==88&&d==99))&&!(a==198&&(c==18||c==19||c==51&&d==100))&&!(a==203&&c==0&&d==113);}
        // Public global-unicast IPv6 only; reject translation/tunnel/documentation ranges.
        return b.length==16&&(b[0]&0xe0)==0x20&&!((b[0]&255)==0x20&&(b[1]&255)==0x01&&((b[2]&255)<2||(b[2]&255)==0x0d&&(b[3]&255)==0xb8))&&!((b[0]&255)==0x20&&(b[1]&255)==0x02)&&!((b[0]&255)==0x3f&&(b[1]&255)==0xff&&(b[2]&0xf0)==0);
    }
}
