package net.nginxmanager.apply;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class ApplyInProgressException extends RuntimeException {

    public ApplyInProgressException() {
        super("another apply is in progress");
    }
}
