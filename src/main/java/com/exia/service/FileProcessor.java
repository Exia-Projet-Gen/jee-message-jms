/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.exia.service;

import com.exia.domain.JAXFile;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 *
 * @author hyaci
 */
@MessageDriven(activationConfig = {
    @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = "jms/decodingQueue")
    ,
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue")
})
public class FileProcessor implements MessageListener {
        
    public FileProcessor() {
    }
    
    private final String EMAIL_PATTEN = "([a-z0-9_.-]+)@([a-z0-9_.-]+[a-z])"; 
    private final double MATCH_PERCENT_NEEDED = 0.5;
    private final int NUMBER_EXTRACTED_WORDS = 5;
    
    @Override
    public void onMessage (Message message) {
        try {
                        
            JAXFile decodingMessage = new JAXFile();
            decodingMessage.setDecodedText(message.getBody(String.class));
            decodingMessage.setKey(message.getStringProperty("keyValue"));           
            decodingMessage.setFileName(message.getStringProperty("fileName"));                  

            JAXFile checkedMessage = checkMessage(decodingMessage);
                        
            if (checkedMessage.getMatchPercent() > MATCH_PERCENT_NEEDED) {               
                saveFileInfo(checkedMessage);
            }
            
            if (checkedMessage.getMailAddress() != null) {
                // if mail found => call .NET WS
                checkedMessage.setDecodedText(decodingMessage.getDecodedText());
                notifyPlatform(checkedMessage);
                System.out.println("sending notification to .net platform");
            }
            
        }catch(JMSException ex) {
            Logger.getLogger(FileProcessor.class.getName()).log(Level.SEVERE, null, ex);
        }
        
    }
    
    public JAXFile checkMessage(JAXFile message) {
               
        List<String> dictionary = getWordsFromDictionary();
        // Find the number of words in the decoded text
        String decodedText = message.getDecodedText();
        ArrayList<String> decodedWordList = new ArrayList<String>(Arrays.asList(decodedText.split(" ")));
        ArrayList<String> sortedWordList = new ArrayList<String>(Arrays.asList(decodedText.split(" ")));
        Collections.sort(sortedWordList,new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                String s1 = (String) o1;
                String s2 = (String) o2;
                return s1.toLowerCase().compareTo(s2.toLowerCase());
            }
        });
        int decodedWordsLength = sortedWordList.size();

        // Find the number of french words in the decoded text
        int frenchWordsLength = countFrenchWords(sortedWordList, dictionary);
        
        // get the percentage of french words in the text
        double matchPercent = (float) frenchWordsLength / decodedWordsLength;

        String mail = null; 
        // if matching percentage is enough high, search the mail address
        if ( matchPercent > MATCH_PERCENT_NEEDED) {
            mail = findMailAddress(decodedText);
        }
        
        JAXFile processedFile = new JAXFile();
        processedFile.setDecodedText(extractFirstWords(decodedWordList));
        processedFile.setFileName(message.getFileName());
        processedFile.setKey(message.getKey());
        processedFile.setMailAddress(mail);
        processedFile.setMatchPercent(matchPercent);
            
        return processedFile;
    }
    
    private String findMailAddress(String decodedText) {
               
        // Regex to find an email address
        Pattern p = Pattern.compile(EMAIL_PATTEN);
        // Search if there is a match with the regex
        Matcher m = p.matcher(decodedText);

        String result = "";
        List<String> emails = new ArrayList();
        while (m.find())
        {
            String email = m.group(0);

            emails.add(email);
        }
        
        //Design pattern implicite
        for (int i=0; i<emails.size(); i++) {
            if (i < 1) {
                result += emails.get(i);
                result += " ";
            } else if(i == emails.size()-1) {
                result += emails.get(i);
            }
        }
        
        return result;
    }
    
    private String extractFirstWords(List<String> decodedWordsList) {
        
        String extractedWords = "";
        
        for (int i=0; i<decodedWordsList.size(); i++) {
            if (i < NUMBER_EXTRACTED_WORDS) {
                   extractedWords += decodedWordsList.get(i);
                   extractedWords += " ";
            }   
        }
        
        return extractedWords;
    }
    
    private int countFrenchWords(List<String> decodedWordsList, List<String> dictionary) {
        
        int wordsCount = 0;
        String prevWord = "";
               
        // Design pattern implicite (iterator)
        for (String word : decodedWordsList) {
            if (prevWord.equals(word.toLowerCase()) || dictionary.contains(word.toLowerCase())) {
                wordsCount++;
            }
        }
              
        return wordsCount;
    }
    
    private ArrayList<String> getWordsFromDictionary() {
        
        Client client = ClientBuilder.newClient();
        WebTarget resource = client.target("http://localhost:10080/exia-rest-crud/crud/dictionary/");

        Builder request = resource.request();
        request.accept(MediaType.APPLICATION_JSON);
        Response response = request.get();
        
        ArrayList<String> words = new ArrayList<>();        
        
        String jsonContent = response.readEntity(String.class);
                
        try(JsonReader jreader = Json.createReader(new StringReader(jsonContent));){
            JsonArray jArray = jreader.readArray();
            for(int i = 0;i<jArray.size();i++){
                JsonObject jObject = jArray.getJsonObject(i);
                String word = jObject.getString("value");
                words.add(word);
            }
        }
       
        Collections.sort(words,new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                String s1 = (String) o1;
                String s2 = (String) o2;
                return s1.toLowerCase().compareTo(s2.toLowerCase());
            }
        });
        
        return words;
     }
     
    private void saveFileInfo(JAXFile checkedFile) {
         
        Client client = ClientBuilder.newClient();
        WebTarget resource = client.target("http://localhost:10080/exia-rest-crud/crud/decodedFile/save");

        Response response = resource.request(MediaType.APPLICATION_JSON)
                .post(Entity.json(checkedFile));
    }
    
    private void notifyPlatform(JAXFile checkedMessage) {
        Client client = ClientBuilder.newClient();
        WebTarget resource = client.target("http://10.162.129.122:8090/Stoper/api/BruteForce");

        Response response = resource.request(MediaType.APPLICATION_JSON)
                .post(Entity.json(checkedMessage));
        
        System.out.println(response);
    }
}
