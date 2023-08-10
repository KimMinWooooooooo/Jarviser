package com.ssafy.jarviser.controller;

import com.ssafy.jarviser.domain.AudioMessage;
import com.nimbusds.jose.shaded.gson.Gson;
import com.ssafy.jarviser.domain.Meeting;
import com.ssafy.jarviser.dto.RequestMeetingIdDto;
import com.ssafy.jarviser.dto.ResponseAudioMessage;
import com.ssafy.jarviser.dto.ResponseMessage;
import com.ssafy.jarviser.security.JwtService;
import com.ssafy.jarviser.service.MeetingService;
import com.ssafy.jarviser.service.OpenAIService;
import com.ssafy.jarviser.util.AESEncryptionUtil;
import io.swagger.v3.core.util.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONArray;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = {"*"})
@RequestMapping("meeting")
public class MeetingController {
    private final JwtService jwtService;
    private final OpenAIService openAIService;
    private final MeetingService meetingService;
    private final SimpMessagingTemplate messagingTemplate;
    private final AESEncryptionUtil aesEncryptionUtil;
    private final Gson gson = new Gson();

    @PostMapping(value = "/transcript", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> transcript(@RequestParam("file") MultipartFile file, Long meetingId) throws IOException {
        Map<String, String> resultMap = new HashMap<>();
        HttpStatus status = null;
        String filePath = "audio/" + file.getOriginalFilename();
        log.debug(filePath);

        //TODO: 추후 MultiPartFile을 File로 즉각 변환해본 후 성능 테스트해보기
        try (
                FileOutputStream fos = new FileOutputStream(filePath);
                // 파일 저장할 경로 + 파일명을 파라미터로 넣고 fileOutputStream 객체 생성하고
                InputStream is = file.getInputStream();) {

            int readCount = 0;
            byte[] buffer = new byte[1024];

            while ((readCount = is.read(buffer)) != -1) {
                //  파일에서 가져온 fileInputStream을 설정한 크기 (1024byte) 만큼 읽고
                fos.write(buffer, 0, readCount);
                // 위에서 생성한 fileOutputStream 객체에 출력하기를 반복한다
            }
        } catch (Exception ex) {
            throw new RuntimeException("file Save Error");
        }
        try {
            String textResponse = openAIService.whisperAPICall(filePath).block();
            assert textResponse != null;

            Map<String, String> responseMap = new HashMap<>();
            responseMap.put("userId", "임시 유저 이름");
            responseMap.put("type", "stt");
            responseMap.put("content", (String) gson.fromJson(textResponse, HashMap.class).get("text"));

            String response = gson.toJson(responseMap).toString();
            messagingTemplate.convertAndSend("/topic/meeting/" + meetingId, response);
            resultMap.put("text", textResponse);
        } catch (Exception e) {
            log.error("텍스트 보내기 실패 : {}", e);
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        return new ResponseEntity<>(resultMap, status.OK);
    }


    //미팅생성
    @PostMapping("/create")
    public ResponseEntity<Map<String ,Object>> createMeeting(
            @RequestHeader("Authorization") String token,
            @RequestBody String meetingName)
    {
        log.debug("CreateMeeting............................create meetingName:" + meetingName);

        Map<String, Object> responseMap = new HashMap<>();
        HttpStatus httpStatus = null;
        token = token.split(" ")[1];
        try {
            Long hostId = jwtService.extractUserId(token);
            Meeting meeting = meetingService.createMeeting(hostId,meetingName);
            String encryptedKey = meeting.getEncryptedKey();
            httpStatus = HttpStatus.ACCEPTED;
            responseMap.put("encryptedKey",encryptedKey);

        } catch (Exception e) {
            log.error("미팅 생성 실패 : {}", e);
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        return new ResponseEntity<>(responseMap, httpStatus);
    }

    //미팅 참여
    @PostMapping("/joinMeeting")
    public ResponseEntity<Map<String,Object>> joinMeeting(
            @RequestHeader("Authorization") String token,
            @RequestBody String encryptedKey){

        Map<String, Object> resultMap = new HashMap<>();
        HttpStatus status = null;

        try {
            long meetingId = Long.parseLong(aesEncryptionUtil.decrypt(encryptedKey));
            Meeting meeting = meetingService.findMeetingById(meetingId);
            log.debug("JoinMeeting............................Join meetingName:" + meeting.getMeetingName());
            Long joinUserId = jwtService.extractUserId(token);
            meetingService.joinMeeting(joinUserId, meeting);
            resultMap.put("meeting", meeting);
            status = HttpStatus.ACCEPTED;
        } catch (Exception e) {
            log.error("미팅 참여 실패 : {}", e);
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        return new ResponseEntity<>(resultMap, status);
    }

    //미팅 오디오 메시지 불러오는 api
    @GetMapping("/audiomessage")
    public ResponseEntity<Map<String,Object>> meetingDetail(
            @RequestHeader("Authorization") String token,
            @RequestBody RequestMeetingIdDto requestMeetingIdDto
    ){
        Map<String,Object> response = new HashMap<>();
        HttpStatus httpStatus = HttpStatus.ACCEPTED;
        try{

            Meeting meeting = meetingService.findMeetingById(requestMeetingIdDto.getMeetingId());
            List<AudioMessage> audioMessages = meeting.getAudioMessages();
            List<ResponseAudioMessage> responseAudioMessages = new ArrayList<>();

            for(AudioMessage audioMessage : audioMessages){
                responseAudioMessages.add(new ResponseAudioMessage(audioMessage.getUserName(),audioMessage.getContent(),audioMessage.getSpeechLength()));
            }
            response.put("audioMessages",responseAudioMessages);
            httpStatus = HttpStatus.OK;

        }catch (Exception e){
            httpStatus = HttpStatus.NOT_ACCEPTABLE;
            throw new RuntimeException(e);
        }
        return new ResponseEntity<>(response,httpStatus);
    }

    //미팅 발화자들 마다 발화 비율 api
    @GetMapping("/speech")
    public ResponseEntity<Map<String,Object>> meetingSpeech(
            @RequestHeader("Authorization") String token,
            @RequestBody RequestMeetingIdDto requestMeetingIdDto
    ){
        Map<String,Object> response = new HashMap<>();
        Map<String,Integer> nameSpeech = new HashMap<>();

        //todo 퍼센테이지 계산하는 로직 추가
        Map<String,Double> nameSpeechPercent = new HashMap<>();
        int total = 0;
        HttpStatus httpStatus = HttpStatus.ACCEPTED;
        try{

            Meeting meeting = meetingService.findMeetingById(requestMeetingIdDto.getMeetingId());
            List<AudioMessage> audioMessages = meeting.getAudioMessages();


            for(AudioMessage audioMessage : audioMessages){
                String userName = audioMessage.getUserName();
                int speechLength = audioMessage.getSpeechLength();
                total += speechLength;
                if(!nameSpeech.containsKey(userName)){
                    nameSpeech.put(userName,speechLength);
                }else{
                    int length = nameSpeech.get(userName);
                    nameSpeech.put(userName,length + speechLength);
                }
            }
            response.put("speechStatics",nameSpeech);
            httpStatus = HttpStatus.OK;

        }catch (Exception e){
            httpStatus = HttpStatus.NOT_ACCEPTABLE;
            throw new RuntimeException(e);
        }
        return new ResponseEntity<>(response,httpStatus);
    }
    //미팅 조회
    //미팅 참여자 조회
    //미팅 통계 상세보기
    //리포트 열람
    //메시지 보내기
    @PostMapping(value = "/message", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> getMessage(
            @RequestHeader("Authorization") String token,
            Long meetingId, String content) throws InterruptedException {

        token = token.split(" ")[1];
        String userName = "";
        try {
            Long userId = jwtService.extractUserId(token);
            userName = jwtService.extractUserName(token);
        } catch (Exception e) {
            log.error("아이디 뽑아내기 실패", e);
        }

        Map<String, String> responseMap = new HashMap<>();
        responseMap.put("userId", userName.toString());
        responseMap.put("type", "chat");
        responseMap.put("content", content);
        String response = gson.toJson(responseMap).toString();

        messagingTemplate.convertAndSend("/topic/meeting/" + meetingId, response);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
