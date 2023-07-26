package com.ssafy.jarviser.controller;

import com.ssafy.jarviser.domain.User;
import com.ssafy.jarviser.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/user")
@Slf4j
public class UserController {

    @Autowired
    UserService userService;

    private static final String SUCCESS = "success";
    private static final String FAIL = "fail";

    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signUp(@RequestBody User user) {
        log.debug("User............................regist user:" + user);

        Map<String, Object> resultMap = new HashMap<>();
        HttpStatus status = null;

        try {
            userService.regist(user);
            resultMap.put("message", SUCCESS);
            status = HttpStatus.ACCEPTED;
        } catch (Exception e) {
            //FIXME : 모든 회원 가입 실패에 대하여 처리가 필요함
            log.error("회원가입 실패 : {}", e);
            resultMap.put("message", FAIL);
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        return new ResponseEntity<>(resultMap, status);
    }


}
