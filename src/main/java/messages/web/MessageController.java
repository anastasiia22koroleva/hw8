package messages.web;

import messages.service.MessageDto;
import messages.service.MessageService;
import messages.service.NewMessage;
import org.hibernate.LazyInitializationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * Принимает массив сообщений: [{"author": "user1", "text": "привет"}, ...]
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody List<NewMessage> messages) {
        int saved = messageService.saveAll(messages);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("saved", saved, "total", messageService.count()));
    }

    @GetMapping("/nplus1")
    public List<MessageDto> nPlusOne() {
        return messageService.getMessagesNPlusOne();
    }

    @GetMapping("/optimized")
    public List<MessageDto> optimized() {
        return messageService.getMessagesOptimized();
    }

    @GetMapping("/{id}")
    public MessageDto get(@PathVariable Long id) {
        return messageService.getMessage(id);
    }

    @GetMapping("/{id}/fail")
    public MessageDto fail(@PathVariable Long id) {
        return messageService.getMessageToFail(id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(LazyInitializationException.class)
    public ResponseEntity<Map<String, String>> lazy(LazyInitializationException e) {
        return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getClass().getSimpleName(), "message", e.getMessage()));
    }
}
