package com.ssafy.jarviser.service;

import com.nimbusds.jose.shaded.gson.Gson;
import com.ssafy.jarviser.domain.AudioMessage;
import com.ssafy.jarviser.domain.User;
import com.ssafy.jarviser.exception.ClientException;
import com.ssafy.jarviser.exception.ServerException;
import com.ssafy.jarviser.repository.AudioMessageRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.io.InputStream;
import java.time.ZoneId;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class AudioServiceImp implements AudioService {
    private final OpenAIService openAIService;
    private final MeetingService meetingService;
    private final AudioMessageRepository audioMessageRepository;
    private final UserService userService;
    private final HashMap<Long, Long> indexMap = new HashMap<>();
    private final Gson gson = new Gson();
    private final String projectPath = System.getProperty("user.dir");
    private final String beforeAudioPath = projectPath + "/audio/preprocess/beforeAudio.webm";
    private final String afterAudioPath = projectPath + "/audio/preprocess/afterAudio.webm";

    @Override
    public Long getTimeOfAudio(MultipartFile audioFile) {
        try {
            // MultipartFile을 InputStream으로 변환
            InputStream inputStream = audioFile.getInputStream();
            inputStream.skip(44); //표준 헤더만큼 넘기기

            // InputStream을 AudioInputStream으로 변환
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(inputStream);

            // AudioFileFormat 가져오기
            AudioFileFormat fileFormat = AudioSystem.getAudioFileFormat(audioInputStream);

            // 프레임 크기와 프레임 레이트 가져오기
            int frameSize = fileFormat.getFormat().getFrameSize();
            float frameRate = fileFormat.getFormat().getFrameRate();

            // 파일 크기 가져오기
            long audioFileLength = audioFile.getSize();

            // 밀리초 단위의 시간 계산
            float durationInSeconds = (audioFileLength / (frameSize * frameRate));
            long durationInMilliseconds = (long) (durationInSeconds * 1000);

            return durationInMilliseconds;
        } catch (Exception e) {
            log.error("get time of audio error", e);
            throw new ClientException("get time of audio error");
        }
    }

    @Override
    public String saveAudioFile(String mId, long userId, long startTime, MultipartFile audioFile) {
        String filePath = projectPath +"/" + "audio/" + mId + "/" + userId + "/" + startTime + ".webm";
        try {
            File savedFile = new File(filePath);
            if (!savedFile.getParentFile().exists()) {
                savedFile.getParentFile().mkdirs();
            }
            audioFile.transferTo(savedFile);
            return filePath;
        } catch (Exception e) {
            log.error("save audio error", e);
            throw new ServerException("save audio error");
        }
    }



    private String createPreprocessAudioFile(String filePath) {
        String tempFilePath = projectPath + "/audio/temp/" + System.currentTimeMillis() + ".webm";

        String command = String.format(
                "ffmpeg -i %s -i %s -i %s -filter_complex [0:a][1:a][2:a]concat=n=3:v=0:a=1[aout] -map [aout] %s",
                beforeAudioPath, filePath, afterAudioPath, tempFilePath);

        try {
            Process process = Runtime.getRuntime().exec(command);
            process.waitFor();
            return tempFilePath;
        } catch (Exception e) {
            log.error("save audio error", e);
            throw new ServerException("save audio error");
        }
    }

    @Override
    public void removeAudioFile(String filePath) {
        try{
            File tempFile = new File(filePath);
            tempFile.delete();
        } catch (Exception e) {
            log.error("remove audio error", e);
            throw new ServerException("remove audio error");
        }
    }


    private String postprocessStt(String response) {
        //pre : 안녕하세요 or 안녕하세요.
        //post : 감사합니다 or 감사합니다.
        String[] responseArray = response.split(" ");
        StringBuilder sb = new StringBuilder();
        if(!responseArray[0].contains("안녕하세요")){
            sb.append(responseArray[0]);
        }
        for (int i = 1; i < responseArray.length-1; i++) {
            sb.append(responseArray[i]);
            sb.append(" ");
        }
        if(!responseArray[responseArray.length -1].contains("감사합니다")){
            sb.append(responseArray[responseArray.length -1]);
        }
        return sb.toString();
    }

    @Override
    public String getStt(String filePath) {
        String stt;
        try {
            String tempFilePath = createPreprocessAudioFile(filePath);
            String textResponse = openAIService.whisperAPICall(tempFilePath).block();
            stt = postprocessStt((String) gson.fromJson(textResponse, HashMap.class).get("text"));
            removeAudioFile(tempFilePath);
            return stt;
        } catch (Exception e) {
            log.error("get stt error", e);
            throw new ServerException("get stt error");
        }
    }

    @Override
    @Transactional
    public Long createAudioMessage(Long userId, String mId, Long StartTime, String filePath, String stt) {
        try {
            AudioMessage audioMessage = AudioMessage.builder()
                    .user(userService.findUserById(userId))
                    .meeting(meetingService.findMeetingById(Long.parseLong(mId)))
                    .startTime(new Date(StartTime).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime())
                    .filePath(filePath)
                    .content(stt)
                    .speechLength(stt.length())
                    .priority(getIndex(Long.parseLong(mId)))
                    .build();
            audioMessageRepository.save(audioMessage);
            return audioMessage.getId();
        } catch (Exception e) {
            log.error("create audio message error", e);
            throw new ServerException("create audio message error");
        }
    }

    private synchronized Long getIndex(Long mId) {
        if (!indexMap.containsKey(mId)) {
            indexMap.put(mId, 1024L);
        }
        Long index = (indexMap.get(mId));
        indexMap.put(mId, index + 1024L);
        return index;
    }

    @Override
    public Map<String, Double> staticsOfAudioMessages(List<AudioMessage> audioMessages) throws Exception {
        // TODO: Exception 처리 후 throws Exception 삭제
        Map<String, Integer> countOfStatic = new HashMap<>();
        Map<String, Double> staticOfAudioMessages = new HashMap<>();

        int totalLength = 0;

        for (AudioMessage audioMessage : audioMessages) {
            Long userId = audioMessage.getId();
            // TODO: userId를 사용하여 분류가 필요함. name은 겹칠 수 있음.
            String name = userService.findUserById(userId).getName();
            int length = audioMessage.getSpeechLength();
            totalLength += length;

            if (!countOfStatic.containsKey(name)) {
                countOfStatic.put(name, length);
            } else {
                int prevLength = countOfStatic.get(name);
                countOfStatic.put(name, length + prevLength);
            }
        }

        // getKeys
        Set<String> names = countOfStatic.keySet();
        for (String name : names) {
            double percentage = (double) countOfStatic.get(name) * 100 / totalLength;
            staticOfAudioMessages.put(name, percentage);
        }
        return staticOfAudioMessages;
    }

    @Override
    public AudioMessage findByAudioMessageId(long audioMessageId) {
        return audioMessageRepository.findById(audioMessageId)
                .orElseThrow(() -> new IllegalArgumentException("No AudioMessage found with id: " + audioMessageId));
    }

    @Override
    public Long updateByAudioMessageId(long audioMessageId, String changedContext) {
        //기존 메시지 찾기
        AudioMessage existingAudioMessage = audioMessageRepository.findById(audioMessageId)
                .orElseThrow(() -> new IllegalArgumentException("No AudioMessage found with id: " + audioMessageId));

        // 오디오 메시지 업데이트
        existingAudioMessage.setContent(changedContext);

        // 변경 사항을 저장하고 반환
        audioMessageRepository.save(existingAudioMessage);
        return audioMessageId;
    }


}
