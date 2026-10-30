package net.nginxmanager.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Optional;

/** 로그인 코드 화면과 내 계정의 2단계 인증 켜기·끄기(017). */
@Controller
public class TwoFactorController {

    private final TwoFactorService twoFactor;

    public TwoFactorController(TwoFactorService twoFactor) {
        this.twoFactor = twoFactor;
    }

    @GetMapping(TwoFactorLogin.CODE_PAGE)
    public String codePage(HttpServletRequest request) {
        return TwoFactorLogin.pendingUser(request) == null ? "redirect:/" : "login-code";
    }

    @PostMapping(TwoFactorLogin.CODE_PAGE)
    public String verify(@RequestParam(defaultValue = "") String code, HttpServletRequest request,
                         HttpServletResponse response, Authentication authentication) {
        String username = TwoFactorLogin.pendingUser(request);
        if (username == null) {
            return "redirect:/";
        }
        return switch (twoFactor.verifyLogin(username, code)) {
            case OK -> {
                request.getSession().removeAttribute(TwoFactorLogin.PENDING);
                // 코드까지 맞은 뒤 세션 아이디를 바꾼다(비밀번호 단계의 세션을 이어 쓰지 않게)
                request.changeSessionId();
                yield "redirect:/";
            }
            case WRONG -> "redirect:" + TwoFactorLogin.CODE_PAGE + "?error";
            case LOCKED -> {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                yield "redirect:/login?error";
            }
        };
    }

    @GetMapping("/account/2fa")
    public String setup(Authentication authentication, Model model) {
        Optional<TwoFactorService.Setup> setup = twoFactor.prepare(authentication.getName());
        if (setup.isEmpty()) {
            return "redirect:/account";
        }
        model.addAttribute("setup", setup.get());
        return "account-2fa";
    }

    @PostMapping("/account/2fa")
    public String enable(@RequestParam(defaultValue = "") String code, Authentication authentication, Model model) {
        Optional<List<String>> codes = twoFactor.enable(authentication.getName(), code);
        if (codes.isEmpty()) {
            return "redirect:/account/2fa?error";
        }
        model.addAttribute("recoveryCodes", codes.get());
        model.addAttribute("enabled", true);
        return "account-recovery-codes";
    }

    @PostMapping("/account/2fa/disable")
    public String disable(@RequestParam(defaultValue = "") String currentPassword, Authentication authentication) {
        return twoFactor.disable(authentication.getName(), currentPassword)
                ? "redirect:/account?2fa-off"
                : "redirect:/account?2fa-error";
    }

    @PostMapping("/account/2fa/recovery-codes")
    public String regenerate(@RequestParam(defaultValue = "") String currentPassword,
                             Authentication authentication, Model model) {
        Optional<List<String>> codes = twoFactor.regenerateRecoveryCodes(authentication.getName(), currentPassword);
        if (codes.isEmpty()) {
            return "redirect:/account?2fa-error";
        }
        model.addAttribute("recoveryCodes", codes.get());
        model.addAttribute("enabled", false);
        return "account-recovery-codes";
    }
}
