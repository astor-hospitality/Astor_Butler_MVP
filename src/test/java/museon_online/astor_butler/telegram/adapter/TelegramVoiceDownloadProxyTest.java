package museon_online.astor_butler.telegram.adapter;

import museon_online.astor_butler.speech.SpeechToTextService;
import museon_online.astor_butler.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The voice file is fetched from Telegram the same way the bot reaches Telegram: through its proxy when one is set. */
class TelegramVoiceDownloadProxyTest {

    @Test
    void httpProxyOfTheBotIsUsedForTheVoiceFile() {
        Proxy proxy = service("HTTP", "astor-tg-awg", 8888).downloadProxy();

        assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP);
        InetSocketAddress address = (InetSocketAddress) proxy.address();
        assertThat(address.getHostString()).isEqualTo("astor-tg-awg");
        assertThat(address.getPort()).isEqualTo(8888);
    }

    @Test
    void socksProxyMapsToSocks() {
        assertThat(service("socks5", "tg-proxy", 1080).downloadProxy().type()).isEqualTo(Proxy.Type.SOCKS);
    }

    @Test
    void noProxyOrIncompleteProxyGoesDirect() {
        assertThat(service("NO_PROXY", "", 0).downloadProxy()).isEqualTo(Proxy.NO_PROXY);
        assertThat(service("HTTP", "", 8888).downloadProxy()).isEqualTo(Proxy.NO_PROXY);
        assertThat(service("HTTP", "astor-tg-awg", 0).downloadProxy()).isEqualTo(Proxy.NO_PROXY);
    }

    private static TelegramVoiceTranscriptionService service(String type, String host, int port) {
        TelegramVoiceTranscriptionService service = new TelegramVoiceTranscriptionService(
                mock(SpeechToTextService.class), mock(ObjectStorageService.class));
        ReflectionTestUtils.setField(service, "proxyType", type);
        ReflectionTestUtils.setField(service, "proxyHost", host);
        ReflectionTestUtils.setField(service, "proxyPort", port);
        return service;
    }
}
