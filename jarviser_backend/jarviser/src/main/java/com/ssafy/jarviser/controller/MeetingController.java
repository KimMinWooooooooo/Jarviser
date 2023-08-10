package com.ssafy.jarviser.controller;

import com.ssafy.jarviser.domain.AudioMessage;
import com.ssafy.jarviser.domain.Meeting;
import com.ssafy.jarviser.dto.RequestMeetingIdDto;
import com.ssafy.jarviser.dto.ResponseAudioMessage;
import com.ssafy.jarviser.security.JwtService;
import com.ssafy.jarviser.service.MeetingService;
import com.ssafy.jarviser.service.OpenAIService;
import com.ssafy.jarviser.util.AESEncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

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

}
