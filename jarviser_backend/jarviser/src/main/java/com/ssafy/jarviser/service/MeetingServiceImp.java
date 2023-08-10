package com.ssafy.jarviser.service;

import com.ssafy.jarviser.domain.*;
import com.ssafy.jarviser.dto.ResponseAudioMessage;
import com.ssafy.jarviser.repository.AudioMessageRepository;
import com.ssafy.jarviser.repository.MeetingRepository;
import com.ssafy.jarviser.repository.ParticipantRepository;
import com.ssafy.jarviser.repository.UserRepository;
import com.ssafy.jarviser.util.AESEncryptionUtil;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

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

    @Override
    public Meeting createMeeting(Long hostId, String meetingName){
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

        return meeting;
}

    //미팅 참여하기
    @Override
    public void joinMeeting(Long joinUserId, Meeting meeting) {
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
    public Report findMeetingStaticsByMeetingId(long meetingId) {

        return meetingRepository.findMeetingReportByMeetingId(meetingId);
    }

    @Override
    public List<Meeting> findMeetingListByUserId(long userid) {
        return meetingRepository.findAllMeetingByUserId(userid);
    }

    @Override
    public Report meetingReport(long meetingId) {

        return meetingRepository.findMeetingReportByMeetingId(meetingId);
    }

    @Override
    public List<AudioMessage> findAudioMessageByMeetingId(long meetingId) {
        return meetingRepository.findAllAudioMessageByMeetingId(meetingId);
    }

    @Override
    public void addAudioMessageToMeeting(long meetingId, AudioMessage audioMessage) {
        Meeting meeting = meetingRepository.findMeetingById(meetingId);
        meeting.addAudioMessage(audioMessage);
        audioMessageRepository.save(audioMessage);
    }


}
