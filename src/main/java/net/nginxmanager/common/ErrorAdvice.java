package net.nginxmanager.common;

import net.nginxmanager.apply.ApplyInProgressException;
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
        model.addAttribute("titleKey", "error.conflict.title");
        model.addAttribute("detailKey", "error.conflict.detail");
        return "error/conflict";
    }

    @ExceptionHandler(ApplyInProgressException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String applyInProgress(Model model) {
        model.addAttribute("titleKey", "error.applying.title");
        model.addAttribute("detailKey", "error.applying.detail");
        return "error/conflict";
    }
}
