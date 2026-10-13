package net.nginxmanager.history;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/** 모든 화면 상단에 외부 변경 경고를 띄울 수 있게 한다. */
@ControllerAdvice
public class DriftAdvice {

    private final DriftDetector driftDetector;

    public DriftAdvice(DriftDetector driftDetector) {
        this.driftDetector = driftDetector;
    }

    @ModelAttribute("drift")
    public List<DriftItem> drift(Authentication authentication) {
        if (authentication == null) {
            return List.of();
        }
        return driftDetector.current();
    }
}
