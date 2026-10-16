package museon_online.astor_butler.telegram.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.storage.ObjectStorageService;
import museon_online.astor_butler.telegram.utils.HostessTopicRouter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramMediaSender {

    private final ResourceLoader resourceLoader;
    private final ObjectStorageService objectStorageService;

    public void sendDocumentIfPresent(Long chatId, Map<String, Object> metadata, AbsSender sender) {
        if (chatId == null || metadata == null || sender == null) {
            return;
        }
        for (Map<String, Object> document : documentMetadata(metadata)) {
            sendDocument(chatId, document, sender);
        }

        Object objectKey = metadata.get("documentObjectKey");
        if (objectKey != null && !objectKey.toString().isBlank()) {
            sendDocument(chatId, Map.of(
                    "objectKey", objectKey.toString(),
                    "filename", text(metadata.get("documentFilename"), "document.pdf"),
                    "caption", text(metadata.get("documentCaption"), "")
            ), sender);
            return;
        }

        Object location = metadata.get("documentResource");
        if (location == null || location.toString().isBlank()) {
            return;
        }
        sendDocument(chatId, Map.of(
                "resource", location.toString(),
                "filename", text(metadata.get("documentFilename"), ""),
                "caption", text(metadata.get("documentCaption"), "")
        ), sender);
    }

    public void sendVideoIfPresent(Long chatId, Map<String, Object> metadata, AbsSender sender) {
        if (chatId == null || metadata == null || sender == null) {
            return;
        }
        Object objectKey = metadata.get("videoObjectKey");
        if (objectKey == null || objectKey.toString().isBlank()) {
            return;
        }
        String filename = text(metadata.get("videoFilename"), "video.mp4");
        String caption = text(metadata.get("videoCaption"), "");
        if ("DOCUMENT".equalsIgnoreCase(text(metadata.get("videoSendMode"), ""))) {
            sendMediaObjectAsDocument(chatId, objectKey.toString(), filename, caption, sender);
            return;
        }
        try (InputStream inputStream = objectStorageService.openMediaObject(objectKey.toString())) {
            sender.execute(HostessTopicRouter.route(sender, SendVideo.builder()
                    .chatId(chatId.toString())
                    .video(new InputFile(inputStream, filename))
                    .caption(caption)
                    .build()));
            log.info("Telegram video sent: chatId={}, objectKey={}", chatId, objectKey);
        } catch (Exception e) {
            log.warn("Telegram video was not sent: {}", e.getMessage());
        }
    }

    private void sendDocument(Long chatId, Map<String, Object> metadata, AbsSender sender) {
        Object objectKey = metadata.get("objectKey");
        if (objectKey != null && !objectKey.toString().isBlank()) {
            sendMediaObjectAsDocument(
                    chatId,
                    objectKey.toString(),
                    text(metadata.get("filename"), "document.pdf"),
                    text(metadata.get("caption"), ""),
                    sender
            );
            return;
        }

        Object location = metadata.get("resource");
        if (chatId == null || location == null || location.toString().isBlank()) {
            return;
        }
        try {
            Resource resource = resourceLoader.getResource(location.toString());
            String filename = text(metadata.get("filename"), resource.getFilename() == null ? "document.pdf" : resource.getFilename());
            try (InputStream inputStream = resource.getInputStream()) {
                sender.execute(HostessTopicRouter.route(sender, SendDocument.builder()
                        .chatId(chatId.toString())
                        .document(new InputFile(inputStream, filename))
                        .caption(text(metadata.get("caption"), ""))
                        .build()));
                log.info("Telegram document sent: chatId={}, resource={}", chatId, location);
            }
        } catch (Exception e) {
            log.warn("Telegram document was not sent: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> documentMetadata(Map<String, Object> metadata) {
        Object value = metadata.get("documents");
        if (!(value instanceof List<?> rawDocuments)) {
            return List.of();
        }
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object rawDocument : rawDocuments) {
            if (rawDocument instanceof Map<?, ?> rawMap) {
                Map<String, Object> document = new LinkedHashMap<>();
                rawMap.forEach((key, documentValue) -> {
                    if (key != null) {
                        document.put(key.toString(), documentValue);
                    }
                });
                documents.add(document);
            }
        }
        return List.copyOf(documents);
    }

    private void sendMediaObjectAsDocument(Long chatId, String objectKey, String filename, String caption, AbsSender sender) {
        if (chatId == null || objectKey == null || objectKey.isBlank()) {
            return;
        }
        try (InputStream inputStream = objectStorageService.openMediaObject(objectKey)) {
            sender.execute(HostessTopicRouter.route(sender, SendDocument.builder()
                    .chatId(chatId.toString())
                    .document(new InputFile(inputStream, filename == null || filename.isBlank() ? "media.bin" : filename))
                    .caption(caption == null ? "" : caption)
                    .build()));
            log.info("Telegram media object sent as document: chatId={}, objectKey={}", chatId, objectKey);
        } catch (Exception e) {
            log.warn("Telegram media object document was not sent: {}", e.getMessage());
        }
    }

    private String text(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }
}
