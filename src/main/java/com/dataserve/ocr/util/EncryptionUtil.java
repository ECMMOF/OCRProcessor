package com.dataserve.ocr.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public class EncryptionUtil {

    private static final String AES = "AES";
    private static final String AES_GCM_NO_PADDING = "AES/GCM/NoPadding";
    private static final int GCM_NONCE_LENGTH = 12; // 12 bytes for GCM nonce
    private static final int GCM_TAG_LENGTH = 16; // 16 bytes for GCM tag
    private static final byte[] STATIC_KEY = "i&have&access&to".getBytes(StandardCharsets.UTF_8); // Example 32-byte key for AES-256

 // Method to get the AES key from environment variables
    private static byte[] getKeyFromEnv() {
        String key = System.getenv("MY_SECRET_KEY");
        if (key == null || key.length() != 32) {
            throw new IllegalArgumentException("Invalid AES key length. Ensure the key is 32 bytes long.");
        }
        return key.getBytes(StandardCharsets.UTF_8);
    }
    // Encrypt a plaintext using AES-GCM
    public static String encrypt(String plaintext)  {
    	try {    		
	        byte[] iv = new byte[GCM_NONCE_LENGTH];
	        SecureRandom random = new SecureRandom();
	        random.nextBytes(iv);
	
	        Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
	        SecretKeySpec keySpec = new SecretKeySpec(STATIC_KEY, AES);
	        GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
	
	        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmParameterSpec);
	
	        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
	
	        byte[] ivAndCiphertext = new byte[GCM_NONCE_LENGTH + ciphertext.length];
	        System.arraycopy(iv, 0, ivAndCiphertext, 0, GCM_NONCE_LENGTH);
	        System.arraycopy(ciphertext, 0, ivAndCiphertext, GCM_NONCE_LENGTH, ciphertext.length);
	
	        return Base64.getEncoder().encodeToString(ivAndCiphertext);
    	}catch (Exception e) {
    		e.printStackTrace();
    	}
    	return "";
    }

    // Decrypt a ciphertext using AES-GCM
    public static String decrypt(String ciphertext)  {
    	try {
	        byte[] ivAndCiphertext = Base64.getDecoder().decode(ciphertext);
	
	        byte[] iv = new byte[GCM_NONCE_LENGTH];
	        byte[] actualCiphertext = new byte[ivAndCiphertext.length - GCM_NONCE_LENGTH];
	
	        System.arraycopy(ivAndCiphertext, 0, iv, 0, GCM_NONCE_LENGTH);
	        System.arraycopy(ivAndCiphertext, GCM_NONCE_LENGTH, actualCiphertext, 0, actualCiphertext.length);
	
	        Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
	        SecretKeySpec keySpec = new SecretKeySpec(STATIC_KEY, AES);
	        GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
	
	        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmParameterSpec);
	
	        byte[] decryptedBytes = cipher.doFinal(actualCiphertext);
	        return new String(decryptedBytes, StandardCharsets.UTF_8);
    	}catch (Exception e) {
    		e.printStackTrace();
    	}
		return "";
    }

    public static void main(String[] args) {
        try {
            String plaintext = "fntadmin";
            System.out.println("Plaintext: " + plaintext);

            String encryptedText = encrypt(plaintext);
            System.out.println("Encrypted Text: " + encryptedText);

            String decryptedText = decrypt(encryptedText);
            System.out.println("Decrypted Text: " + decryptedText);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    
}