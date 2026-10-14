package net.nginxmanager.common;

import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice
public class ErrorAdvice {

    @ExceptionHandler(ConcurrentEditException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String concurrentEdit(Model model) {
        model.addAttribute("title", "다른 사람이 먼저 저장했습니다");
        model.addAttribute("detail", "화면을 새로 고쳐 최신 내용을 확인한 뒤 다시 저장해 주세요. 입력하신 내용은 저장되지 않았습니다.");
        return "error/conflict";
    }
}
