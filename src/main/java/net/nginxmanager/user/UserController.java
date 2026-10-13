package net.nginxmanager.user;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/users")
public class UserController {

    private final UserRepository userRepository;
    private final UserService userService;

    public UserController(UserRepository userRepository, UserService userService) {
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @GetMapping
    public String list(Model model) {
        if (!model.containsAttribute("invite")) {
            model.addAttribute("invite", UserForms.Invite.empty());
        }
        model.addAttribute("users", userRepository.findAllByOrderByUsernameAsc());
        model.addAttribute("roles", Role.values());
        return "users/list";
    }

    @PostMapping
    public String invite(@Valid @ModelAttribute("invite") UserForms.Invite invite, BindingResult bindingResult,
                         Authentication authentication, Model model) {
        if (!bindingResult.hasErrors()) {
            try {
                userService.invite(invite, authentication.getName());
                return "redirect:/users?invited=" + invite.username();
            } catch (DuplicateUsernameException e) {
                bindingResult.rejectValue("username", "msg.user.duplicate", "이미 있는 아이디입니다.");
            }
        }
        return list(model);
    }

    @PostMapping("/{id}/role")
    public String changeRole(@PathVariable long id, @RequestParam Role role, @RequestParam long version,
                             Authentication authentication) {
        return guarded(() -> userService.changeRole(id, role, version, authentication.getName()));
    }

    @PostMapping("/{id}/enabled")
    public String setEnabled(@PathVariable long id, @RequestParam boolean enabled, @RequestParam long version,
                             Authentication authentication) {
        return guarded(() -> userService.setEnabled(id, enabled, version, authentication.getName()));
    }

    @PostMapping("/{id}/password")
    public String resetPassword(@PathVariable long id, @RequestParam String password,
                                @RequestParam long version, Authentication authentication) {
        if (password.length() < 10 || password.length() > 100) {
            return "redirect:/users?error=password";
        }
        userService.resetPassword(id, password, version, authentication.getName());
        return "redirect:/users?saved";
    }

    private String guarded(Runnable change) {
        try {
            change.run();
            return "redirect:/users?saved";
        } catch (LastAdminException e) {
            return "redirect:/users?error=last-admin";
        }
    }
}
