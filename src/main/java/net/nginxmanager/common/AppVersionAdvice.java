package net.nginxmanager.common;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 모든 화면의 footer에 관리도구 버전을 보여 줄 수 있게 모델에 넣는다. */
@ControllerAdvice
public class AppVersionAdvice {

    private final AppVersion appVersion;

    public AppVersionAdvice(AppVersion appVersion) {
        this.appVersion = appVersion;
    }

    @ModelAttribute("appVersion")
    public AppVersion appVersion() {
        return appVersion;
    }
}
