package com.ruoyi.warehouse.carriers;

import com.ruoyi.warehouse.support.WarehouseJson;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CarrierVault {
    private final byte[] key;
    public CarrierVault(@Value("${warehouse.carriers.encryption-key:}")String encoded){if(encoded.isBlank()){key=null;return;}try{key=Base64.getDecoder().decode(encoded);if(key.length!=32)throw new IllegalArgumentException();}catch(Exception e){throw new IllegalStateException("Carrier encryption key must be 32 random bytes encoded as Base64");}}
    public boolean configured(){return key!=null;}
    public String seal(String id,Map<String,Object> input){if(key==null)throw new CarrierFailure("CARRIER_KEY_MISSING");var credentials=DpdContract.credentials(input);try{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);return "v1."+Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(cipher(Cipher.ENCRYPT_MODE,id,iv).doFinal(WarehouseJson.write(credentials).getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException e){throw new CarrierFailure("CARRIER_ENCRYPTION_FAILED");}}
    public Map<String,Object> open(String id,String value){if(key==null)throw new CarrierFailure("CARRIER_KEY_MISSING");try{String[] p=value.split("\\.");if(p.length!=3||!p[0].equals("v1"))throw new IllegalArgumentException();byte[] iv=Base64.getDecoder().decode(p[1]);if(iv.length!=12)throw new IllegalArgumentException();return DpdContract.credentials(WarehouseJson.map(new String(cipher(Cipher.DECRYPT_MODE,id,iv).doFinal(Base64.getDecoder().decode(p[2])),StandardCharsets.UTF_8)));}catch(Exception e){throw new CarrierFailure("CARRIER_DECRYPTION_FAILED");}}
    private Cipher cipher(int mode,String id,byte[] iv)throws GeneralSecurityException{var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));c.updateAAD(("warehouse:dpd-uk:"+id).getBytes(StandardCharsets.UTF_8));return c;}
}
