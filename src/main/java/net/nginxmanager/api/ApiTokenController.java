package net.nginxmanager.api;

import net.nginxmanager.common.Messages;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 설정 > API 토큰(ADMIN, 033). 만든 토큰 값은 바로 다음 화면에서 한 번만 보여 준다. */
@Controller
@RequestMapping("/settings/api-tokens")
public class ApiTokenController {

    private final ApiTokenRepository repository;
    private final ApiTokenService tokens;
    private final Messages messages;

    public ApiTokenController(ApiTokenRepository repository, ApiTokenService tokens, Messages messages) {
        this.repository = repository;
        this.tokens = tokens;
        this.messages = messages;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("tokens", repository.findAllByOrderByIdAsc());
        return "settings/api-tokens";
    }

    @PostMapping
    public String create(@RequestParam String name, Authentication authentication, RedirectAttributes redirect) {
        try {
            ApiTokenService.Created created = tokens.create(name, authentication.getName());
            redirect.addFlashAttribute("createdName", created.token().getName());
            redirect.addFlashAttribute("createdValue", created.value());
        } catch (ApiTokenService.InvalidNameException e) {
            redirect.addFlashAttribute("error", messages.get("msg.apiToken.invalidName"));
        } catch (ApiTokenService.DuplicateNameException e) {
            redirect.addFlashAttribute("error", messages.get("msg.apiToken.duplicateName"));
        }
        return "redirect:/settings/api-tokens";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, Authentication authentication, RedirectAttributes redirect) {
        tokens.delete(id, authentication.getName());
        redirect.addFlashAttribute("notice", messages.get("msg.apiToken.deleted"));
        return "redirect:/settings/api-tokens";
    }
}
