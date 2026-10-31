package net.nginxmanager.stream;

import jakarta.validation.Valid;
import net.nginxmanager.common.Messages;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.config.ConfigFileStore;
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

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** TCP/UDP 스트림 프록시 화면(stream 모듈). */
@Controller
@RequestMapping("/streams")
public class StreamController {

    private final StreamService streamService;
    private final StreamProxyRepository streamRepository;
    private final ConfigFileRepository configFileRepository;
    private final ConfigFileStore store;
    private final StreamConfigRenderer renderer;
    private final Messages messages;

    public StreamController(StreamService streamService, StreamProxyRepository streamRepository,
                            ConfigFileRepository configFileRepository, ConfigFileStore store,
                            StreamConfigRenderer renderer, Messages messages) {
        this.streamService = streamService;
        this.streamRepository = streamRepository;
        this.configFileRepository = configFileRepository;
        this.store = store;
        this.renderer = renderer;
        this.messages = messages;
    }

    /** 목록 한 줄. 설정 파일은 스트림 디렉터리 전체를 한 번에 읽어 붙인다. */
    public record StreamRow(StreamProxy stream, ConfigFile file) {

        public boolean pending() {
            return file != null && file.hasPendingChange();
        }

        public boolean pendingDelete() {
            return file != null && file.isDraftDelete();
        }
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("streams", rows());
        return "streams/list";
    }

    List<StreamRow> rows() {
        Map<String, ConfigFile> files = configFileRepository.findByPathStartingWith(StreamPaths.DIR).stream()
                .collect(Collectors.toMap(ConfigFile::getPath, Function.identity()));
        return streamRepository.findAllByOrderByListenPortAscProtocolAsc().stream()
                .map(stream -> new StreamRow(stream, files.get(stream.getConfigPath())))
                .toList();
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("form", StreamForm.empty());
        return "streams/form";
    }

    @PostMapping("/new")
    public String create(@Valid @ModelAttribute("form") StreamForm form, BindingResult bindingResult,
                         Authentication authentication) {
        if (bindingResult.hasErrors()) {
            return "streams/form";
        }
        try {
            StreamProxy stream = streamService.create(form, authentication.getName());
            return "redirect:/streams/" + stream.getId() + "?saved";
        } catch (PortTakenException e) {
            bindingResult.rejectValue("listenPort", "msg.stream.portTaken", new Object[] {String.valueOf(e.getPort()), e.getUsedBy()},
                    "port taken");
            return "streams/form";
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        StreamProxy stream = streamService.load(id);
        model.addAttribute("form", stream.toForm());
        addDetail(model, stream);
        return "streams/detail";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable long id, @Valid @ModelAttribute("form") StreamForm form,
                         BindingResult bindingResult, Authentication authentication, Model model) {
        StreamProxy stream = streamService.load(id);
        // 프로토콜·포트는 바꾸지 않으므로 그 칸의 검증 오류는 무시한다
        boolean errors = bindingResult.getFieldErrors().stream()
                .anyMatch(e -> !e.getField().equals("listenPort") && !e.getField().equals("listenPortFree")
                        && !e.getField().equals("protocol"));
        if (errors) {
            addDetail(model, stream);
            return "streams/detail";
        }
        streamService.update(id, form, authentication.getName());
        return "redirect:/streams/" + id + "?saved";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, @RequestParam long version, Authentication authentication) {
        streamService.delete(id, version, authentication.getName());
        return "redirect:/streams?deleted";
    }

    /** 폼 값으로 만들 설정을 저장 전에 미리 본다(htmx). */
    @PostMapping("/preview")
    public String preview(@Valid @ModelAttribute("form") StreamForm form, BindingResult bindingResult, Model model) {
        model.addAttribute("preview", bindingResult.hasErrors() ? null : renderer.render(form));
        if (bindingResult.hasErrors() && (form.listenPort() > 0 || form.backends() != null && !form.backends().isBlank())) {
            model.addAttribute("preview", messages.previewErrors(bindingResult));
        }
        return "streams/form :: preview";
    }

    private void addDetail(Model model, StreamProxy stream) {
        ConfigFile file = configFileRepository.findByPath(stream.getConfigPath()).orElse(null);
        String content = file != null && file.getDraftContent() != null ? file.getDraftContent()
                : store.read(stream.getConfigPath());
        model.addAttribute("stream", stream);
        model.addAttribute("file", file);
        model.addAttribute("content", content);
    }
}
