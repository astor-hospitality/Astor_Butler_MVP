import AppIntents
import Foundation
import Security

@available(iOS 16.0, *)
struct StartAstorDemoIntent: AppIntent {
    static let title: LocalizedStringResource = "Начать показ Астор"
    static let description = IntentDescription("Открывает Astor Glasses и запускает учебное приветствие для Яны после подключения очков. Не начинает запись микрофона.")
    static let openAppWhenRun = true
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult {
        UserDefaults.standard.set(Date(), forKey: "AstorSiriStartRequestedAt")
        NSLog("[AstorGlasses] Siri intent: запрошен учебный старт.")
        return .result()
    }
}

// Siri speaks the dialog in its own selected voice; this does not expose or copy
// the Siri synthesis engine to the application. No backend or personal data in this test.
@available(iOS 16.0, *)
struct CheckAstorVoiceIntent: AppIntent {
    static let title: LocalizedStringResource = "Проверить голос Astor"
    static let description = IntentDescription("Siri произнесёт проверочную фразу выбранным системным голосом. Это тест озвучки, без запроса к ИИ.")
    static let openAppWhenRun: Bool = false
    static let authenticationPolicy: IntentAuthenticationPolicy = .alwaysAllowed

    func perform() async throws -> some IntentResult & ProvidesDialog {
        NSLog("[AstorGlasses] Siri intent: тест голоса выполнен.")
        return .result(dialog: "Астор на связи. Это проверка голоса Siri для помощника на смене. Сервер ИИ в этом тесте не используется.")
    }
}

@available(iOS 16.0, *)
struct AskAstorIntent: AppIntent {
    static let title: LocalizedStringResource = "Спросить Astor"
    static let description = IntentDescription("Вопрос информационному API Astor; ответ читает Siri. Статусы задач не меняются.")
    static let openAppWhenRun = false
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Вопрос", requestValueDialog: "Что передать Астору?")
    var question: String

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let text = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.count <= 4000 else { return .result(dialog: "Задайте короткий вопрос Астору.") }
        let stored = UserDefaults.standard.string(forKey: "backendURL") ?? ""
        guard var url = URLComponents(string: stored), url.scheme?.lowercased() == "https", !(url.host ?? "").isEmpty,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else {
            return .result(dialog: "Сервер Астор ещё не подключён. Укажите адрес в приложении. Это проверка голосового интерфейса, а не ответ ИИ.")
        }
        let key: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: "com.astor.glasses.backend", kSecAttrAccount: "access-token", kSecReturnData: true]
        var result: CFTypeRef?
        guard SecItemCopyMatching(key as CFDictionary, &result) == errSecSuccess,
              let bytes = result as? Data, let token = String(data: bytes, encoding: .utf8), !token.isEmpty else {
            return .result(dialog: "Откройте Astor Glasses и сохраните доступ к серверу. Для чтения ключа телефон должен быть разблокирован.")
        }
        let base = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        url.path = (base.isEmpty ? "" : "/" + base) + "/api/glasses/assist"
        guard let target = url.url else { return .result(dialog: "Некорректный адрес Астор.") }
        let requestID = UUID().uuidString
        var request = URLRequest(url: target, timeoutInterval: 25)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["requestId": requestID, "text": text])
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        let session = URLSession(configuration: configuration, delegate: AstorNoRedirect(), delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200, data.count <= 3 * 1024 * 1024,
                  let reply = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let answer = reply["text"] as? String, !answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, answer.count <= 12000,
                  reply["requestId"] as? String == requestID else {
                return .result(dialog: "Астор не вернул подтверждённый ответ. Попробуйте позже или откройте приложение.")
            }
            NSLog("[AstorGlasses] Siri intent: получен ответ информационного API.")
            return .result(dialog: "\(String(answer.prefix(1500)))")
        } catch {
            return .result(dialog: "Нет соединения с Астор. Запрос не выполнен.")
        }
    }
}

private final class AstorNoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

@available(iOS 16.0, *)
struct AstorShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartAstorDemoIntent(),
            phrases: ["\(.applicationName)", "Start \(.applicationName)"],
            shortTitle: "Астор · начать показ",
            systemImageName: "play.circle"
        )
        AppShortcut(
            intent: CheckAstorVoiceIntent(),
            phrases: ["Check the voice of \(.applicationName)"],
            shortTitle: "Проверить голос Astor",
            systemImageName: "waveform"
        )
        AppShortcut(
            intent: AskAstorIntent(),
            phrases: ["Ask \(.applicationName)"],
            shortTitle: "Спросить Astor",
            systemImageName: "bubble.left.and.text.bubble.right"
        )
    }
}

@objc(AstorSiriBridge)
public final class AstorSiriBridge: NSObject {
    @objc public static func refreshShortcuts() {
        if #available(iOS 16.0, *) {
            AstorShortcuts.updateAppShortcutParameters()
        }
    }
}
