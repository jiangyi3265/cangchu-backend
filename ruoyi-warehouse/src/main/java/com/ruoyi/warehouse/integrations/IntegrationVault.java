package com.ruoyi.warehouse.integrations;

import com.ruoyi.warehouse.support.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IntegrationVault {
    private final byte[] key;
    public IntegrationVault(@Value("${warehouse.integrations.encryption-key:}") String encoded) {
        if(encoded.isBlank()){key=null;return;}
        try{key=Base64.getDecoder().decode(encoded);if(key.length!=32)throw new IllegalArgumentException();}
        catch(Exception e){throw new IllegalStateException("Integration encryption key must be 32 random bytes encoded as Base64");}
    }
    public boolean configured(){return key!=null;}
    public String seal(String connection,Map<String,Object> credentials) {
        requireKey();validate(credentials);
        try{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);var cipher=cipher(Cipher.ENCRYPT_MODE,connection,iv);return "v1."+Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(cipher.doFinal(WarehouseJson.write(credentials).getBytes(StandardCharsets.UTF_8)));}
        catch(GeneralSecurityException e){throw new IntegrationFailure("CREDENTIAL_ENCRYPTION_FAILED");}
    }
    public Map<String,Object> open(String connection,String encrypted) {
        requireKey();try{String[] parts=encrypted.split("\\.");if(parts.length!=3||!"v1".equals(parts[0]))throw new IllegalArgumentException();byte[] iv=Base64.getDecoder().decode(parts[1]);if(iv.length!=12)throw new IllegalArgumentException();var decoded=WarehouseJson.map(new String(cipher(Cipher.DECRYPT_MODE,connection,iv).doFinal(Base64.getDecoder().decode(parts[2])),StandardCharsets.UTF_8));validate(decoded);return decoded;}
        catch(Exception e){throw new IntegrationFailure("CREDENTIAL_DECRYPTION_FAILED");}
    }
    private Cipher cipher(int mode,String id,byte[] iv)throws GeneralSecurityException{var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));c.updateAAD(("warehouse:woocommerce:"+id).getBytes(StandardCharsets.UTF_8));return c;}
    private void requireKey(){if(key==null)throw new IntegrationFailure("CREDENTIAL_KEY_MISSING");}
    static void validate(Map<String,Object> data){if(!data.keySet().equals(Set.of("consumerKey","consumerSecret")))throw new IntegrationFailure("CREDENTIAL_FIELDS_INVALID");for(String name:data.keySet()){Object value=data.get(name);if(!(value instanceof String s)||s.length()<4||s.length()>512||s.chars().anyMatch(c->c<33||c>126)||("consumerKey".equals(name)&&s.contains(":")))throw new IntegrationFailure("CREDENTIAL_FIELDS_INVALID");}}
}
