package net.nginxmanager.runtime;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 모든 화면 상단에 nginx 컨테이너와 맞지 않는다는 경고를 띄울 수 있게 한다. */
@ControllerAdvice
public class RuntimeAdvice {

    private final RuntimeCompatibility compatibility;

    public RuntimeAdvice(RuntimeCompatibility compatibility) {
        this.compatibility = compatibility;
    }

    @ModelAttribute("runtimeWarning")
    public RuntimeCompatibility.Warning runtimeWarning(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        return compatibility.current().orElse(null);
    }
}
