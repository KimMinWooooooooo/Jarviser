package com.ssafy.jarviser.service;

import com.ssafy.jarviser.domain.*;
import com.ssafy.jarviser.dto.KeywordStatisticsDTO;
import com.ssafy.jarviser.dto.ParticipantsStaticsDTO;
import com.ssafy.jarviser.repository.*;
import com.ssafy.jarviser.util.AESEncryptionUtil;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class MeetingServiceImp implements MeetingService{

    private final MeetingRepository meetingRepository;
    private final UserRepository userRepository;
    private final ParticipantRepository participantRepository;
    private final AudioMessageRepository audioMessageRepository;
    private final AESEncryptionUtil aesEncryptionUtil;
    private final KeywordStatisticsRepository keywordStatisticsRepository;
    private final OpenAIService openAIService;

    @Override
    public String createMeeting(Long hostId, String meetingName){
        //미팅 객체 생성
        Meeting meeting = Meeting.builder()
                .meetingName(meetingName)
                .hostId(hostId)
                .startTime(LocalDateTime.now())
                .build();
        //이시점에서 미팅이 생성되므로 DB에 미팅저장
        meetingRepository.saveMeeting(meeting);
        //미팅의 pk 를 토대로 encrypt설정
        try {
            meeting.setEncryptedKey(aesEncryptionUtil.encrypt(Long.toString(meeting.getId())));
        }catch (Exception ignored){

        }
        //미팅 - 참여자(호스트) 생성
        User host = userRepository.findById(hostId).orElse(null);
        Participant participant = Participant.participate(host, meeting);
        //호스트로 참여자 설정
        participant.setRole(ParticipantRole.HOST);
        //미팅 - 참여자(호스트) 저장
        participantRepository.joinParticipant(participant);
        return meeting.getEncryptedKey();
}

    //미팅 참여하기(내부적으로는 저장하는 로직까지)
    @Override
    public void joinMeeting(Long joinUserId, long meetingId) {
        Meeting meeting = meetingRepository.findMeetingById(meetingId);
        //참여자가져오기
        User user = userRepository.findById(joinUserId).orElse(null);
        //참여자 - 미팅 생성
        Participant participant = Participant.participate(user, meeting);
        //참여자로 참여자 설정
        participant.setRole(ParticipantRole.PARTICIPANT);
        //참여자 미팅 저장
        participantRepository.joinParticipant(participant);
    }

    @Override
    public Meeting findMeetingById(long meetingId) {
        return meetingRepository.findMeetingById(meetingId);
    }

    @Override
    public List<User> findUserListByMeetingId(long meetingId) {
        return meetingRepository.findUserListByMeetingId(meetingId);
    }



    @Override
    public List<Meeting> findMeetingListByUserId(long userid) {
        return meetingRepository.findAllMeetingByUserId(userid);
    }


    @Override
    public void addAudioMessageToMeeting(long meetingId, AudioMessage audioMessage) {
        Meeting meeting = meetingRepository.findMeetingById(meetingId);
        meeting.addAudioMessage(audioMessage);
        audioMessageRepository.save(audioMessage);
    }

    @Override
    public void addKeywordStatisticsToMeeting(long meetingId, KeywordStatistics keyword) {
        Meeting meeting = meetingRepository.findMeetingById(meetingId);
        meeting.addKeywordStatistics(keyword);
        keywordStatisticsRepository.save(keyword);
    }

    @Override
    public List<AudioMessage> findAllAudioMessage(long meetingId) {
        return audioMessageRepository.findAllByMeetingId(meetingId);
    }

    @Override
    public List<KeywordStatistics> findAllKeywordStatistics(long meetingId) {
        return keywordStatisticsRepository.findAllByMeetingId(meetingId);
    }

    //미팅 아이디 기준으로 계산
    @Override
    public List<KeywordStatisticsDTO> caculateKeywordsStatics(long meetingId) {
        Map<String,Integer> keywordCount = new HashMap<>();
        List<KeywordStatisticsDTO> keywordStatisticsDTOList = new ArrayList<>();

        List<AudioMessage> audioMessages = audioMessageRepository.findAllByMeetingId(meetingId);
        //openAI서비스로 오디오 메시들에 대해서 키워드들 추출
        List<String> keywords = openAIService.chatGTPKeywords(audioMessages);
        int total = 0; // 총 개수
        //추출된 키워드를 통해서 발화내용의 퍼센테이지 구분

        for(String keyword : keywords){
            int keywordHit = 0;
            for(AudioMessage audioMessage : audioMessages){
                String content = audioMessage.getContent();
                total++;
                if(content.contains(keyword)){
                    keywordHit++;
                }
            }
            keywordCount.put(keyword,keywordHit);
        }

        for(String keyword : keywords){
            int hitCount = keywordCount.get(keyword);

            KeywordStatisticsDTO keywordStatisticsDTO = KeywordStatisticsDTO.builder()
                    .keyword(keyword)
                    .percent(100.0 * hitCount / total)
                    .build();

            keywordStatisticsDTOList.add(keywordStatisticsDTO);
        }
        return keywordStatisticsDTOList;
    }

    @Override
    public List<ParticipantsStaticsDTO> caculateParticipantsStatics(long meetingId) {
        Map<Long,Integer> IdMap = new HashMap<>(); // 아이디 값 기준으로 총 발화된 Map
        List<ParticipantsStaticsDTO> participantsStaticsDTOList = new ArrayList<>();
        //오디오 메시지들 다 불러오기
        List<AudioMessage> audioMessages = audioMessageRepository.findAllByMeetingId(meetingId);

        int total = 0; // 총 발화 길이
        for(AudioMessage message : audioMessages){
            long userId = message.getUser().getId();
            int speechLength = message.getSpeechLength();
            total += speechLength;
            if(!IdMap.containsKey(userId)){
                IdMap.put(userId,speechLength);
            }else{
                int prevlength = IdMap.get(userId); // 기존 발화 길이
                IdMap.put(userId,prevlength + speechLength);
            }
        }

        Set<Long> keys = IdMap.keySet();
        for(long userId : keys){
            int speechLength = IdMap.get(userId);
            double percent = 100.0 * speechLength / total;
            User user = userRepository.findById(userId).orElse(null);
            assert user != null;
            ParticipantsStaticsDTO participantsStaticsDTO = ParticipantsStaticsDTO
                    .builder()
                    .id(userId)
                    .name(user.getName())
                    .percentage(percent)
                    .build();

            participantsStaticsDTOList.add(participantsStaticsDTO);

        }

        return participantsStaticsDTOList;
    }


}
