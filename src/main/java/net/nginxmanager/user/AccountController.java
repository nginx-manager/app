package net.nginxmanager.user;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/** 본인 비밀번호 변경. */
@Controller
public class AccountController {

    private final UserService userService;

    public AccountController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/account")
    public String form(Model model) {
        model.addAttribute("form", UserForms.PasswordChange.empty());
        return "account";
    }

    @PostMapping("/account")
    public String change(@Valid @ModelAttribute("form") UserForms.PasswordChange form, BindingResult bindingResult,
                         Authentication authentication) {
        if (bindingResult.hasErrors()) {
            return "account";
        }
        if (!userService.changeOwnPassword(authentication.getName(), form.currentPassword(), form.newPassword())) {
            bindingResult.rejectValue("currentPassword", "msg.account.wrongPassword", "현재 비밀번호가 맞지 않습니다.");
            return "account";
        }
        return "redirect:/account?saved";
    }
}
