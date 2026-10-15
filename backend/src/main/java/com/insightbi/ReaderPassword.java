package com.insightbi;

import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2 credentials for explicitly enabled read-only accounts (no plaintext). */
final class ReaderPassword {
  private static final SecureRandom RANDOM=new SecureRandom();
  private static final int ITERATIONS=210000;
  private ReaderPassword(){}

  static String hash(String password){
    requireStrength(password);
    byte[] salt=new byte[16];RANDOM.nextBytes(salt);
    byte[] output=derive(password,salt,ITERATIONS);
    return "pbkdf2-sha256$"+ITERATIONS+"$"+
      Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(output);
  }

  static void requireStrength(String value){
    if(value==null||value.length()<12||value.length()>128||
       value.chars().allMatch(Character::isWhitespace))
      throw new IllegalArgumentException("普通用户密码长度须为 12 到 128 个字符");
  }

  static boolean matches(String supplied,String stored){
    if(supplied==null||stored==null||supplied.length()>128||supplied.isEmpty())return false;
    try{
      String[] parts=stored.split("\\$",4);
      if(parts.length!=4||!"pbkdf2-sha256".equals(parts[0]))return false;
      int iterations=Integer.parseInt(parts[1]);
      if(iterations!=ITERATIONS)return false;
      byte[] salt=Base64.getDecoder().decode(parts[2]);
      byte[] saved=Base64.getDecoder().decode(parts[3]);
      if(salt.length!=16||saved.length!=32)return false;
      return MessageDigest.isEqual(saved,derive(supplied,salt,iterations));
    }catch(Exception ex){return false;}
  }

  private static byte[] derive(String password,byte[] salt,int rounds){
    char[] chars=password.toCharArray();
    PBEKeySpec spec=new PBEKeySpec(chars,salt,rounds,256);
    try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
    catch(Exception e){throw new IllegalStateException("密码处理失败");}
    finally{spec.clearPassword();Arrays.fill(chars,'\0');}
  }
}
