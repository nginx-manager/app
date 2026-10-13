package net.nginxmanager.user;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class SetupController {

    private final SetupState setupState;
    private final UserService userService;

    public SetupController(SetupState setupState, UserService userService) {
        this.setupState = setupState;
        this.userService = userService;
    }

    @GetMapping("/setup")
    public String form(Model model) {
        requireSetupPending();
        model.addAttribute("form", new SetupForm("", "", ""));
        return "setup";
    }

    @PostMapping("/setup")
    public String create(@Valid @ModelAttribute("form") SetupForm form, BindingResult bindingResult) {
        requireSetupPending();
        if (!form.password().equals(form.passwordConfirm())) {
            bindingResult.rejectValue("passwordConfirm", "mismatch", "비밀번호가 서로 다릅니다.");
        }
        if (bindingResult.hasErrors()) {
            return "setup";
        }
        userService.createInitialAdmin(form.username(), form.password());
        setupState.markCompleted();
        return "redirect:/login?setup";
    }

    private void requireSetupPending() {
        if (setupState.isCompleted()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
