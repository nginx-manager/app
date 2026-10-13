package net.nginxmanager.notify;

import net.nginxmanager.common.Messages;
import net.nginxmanager.common.NotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.regex.Pattern;

/** 알림 채널 설정(ADMIN). */
@Controller
@RequestMapping("/settings/notifications")
public class NotificationController {

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private final NotificationChannelRepository channelRepository;
    private final NotificationService notificationService;
    private final Messages messages;

    public NotificationController(NotificationChannelRepository channelRepository,
                                  NotificationService notificationService, Messages messages) {
        this.messages = messages;
        this.channelRepository = channelRepository;
        this.notificationService = notificationService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("channels", channelRepository.findAllByOrderByIdAsc());
        return "settings/notifications";
    }

    @PostMapping
    public String add(@RequestParam ChannelType type, @RequestParam String target, RedirectAttributes redirect) {
        String value = target.trim();
        boolean valid = type == ChannelType.EMAIL ? EMAIL.matcher(value).matches() : URL.matcher(value).matches();
        if (!valid || value.length() > 500) {
            redirect.addFlashAttribute("error", type == ChannelType.EMAIL
                    ? messages.get("msg.notify.invalidEmail") : messages.get("msg.notify.invalidUrl"));
            return "redirect:/settings/notifications";
        }
        channelRepository.save(new NotificationChannel(type, value));
        return "redirect:/settings/notifications";
    }

    @PostMapping("/{id}/enabled")
    @Transactional
    public String setEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        load(id).setEnabled(enabled);
        return "redirect:/settings/notifications";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id) {
        channelRepository.delete(load(id));
        return "redirect:/settings/notifications";
    }

    @PostMapping("/{id}/test")
    public String test(@PathVariable long id, RedirectAttributes redirect) {
        NotificationService.Delivery delivery = notificationService.send(load(id), new NotificationService.Notice(
                "TEST", messages.get("msg.notify.testTitle"), messages.get("msg.notify.testBody")));
        if (delivery.error() == null) {
            redirect.addFlashAttribute("notice", messages.get("msg.notify.testSent"));
        } else {
            redirect.addFlashAttribute("error", messages.get("msg.notify.testFailed", delivery.error()));
        }
        return "redirect:/settings/notifications";
    }

    private NotificationChannel load(long id) {
        return channelRepository.findById(id).orElseThrow(() -> new NotFoundException("channel " + id));
    }
}
