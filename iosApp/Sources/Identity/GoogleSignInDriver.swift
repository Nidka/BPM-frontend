import ComposeApp
import GoogleSignIn
import Security
import UIKit

/// Adapta el SDK oficial al puerto Kotlin; Firebase y BPM conservan la autorización.
final class GoogleSignInDriver: NSObject, IosGoogleSignInDriver {
    private let clientID: String
    private let serverClientID: String
    let configured: Bool
    private weak var presenter: UIViewController?
    private var pending: ((GoogleAuthResult) -> Void)?
    private var generation = 0

    override init() {
        clientID = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String ?? ""
        serverClientID = Bundle.main.object(forInfoDictionaryKey: "GIDServerClientID") as? String ?? ""
        let schemes = (Bundle.main.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] ?? [])
            .flatMap { $0["CFBundleURLSchemes"] as? [String] ?? [] }
        configured = !clientID.isEmpty && !serverClientID.isEmpty && clientID != serverClientID
            && schemes.contains(clientID.split(separator: ".").reversed().joined(separator: "."))
        super.init()
    }

    func attach(_ controller: UIViewController) { presenter = controller }

    func start(onResult: @escaping (GoogleAuthResult) -> Void) {
        DispatchQueue.main.async { [self] in begin(onResult) }
    }

    private func begin(_ onResult: @escaping (GoogleAuthResult) -> Void) {
        guard pending == nil else { onResult(GoogleAuthResultFailure(code: "GOOGLE_IN_PROGRESS")); return }
        guard configured else { onResult(GoogleAuthResultFailure(code: "GOOGLE_NOT_CONFIGURED")); return }
        guard let presenter, presenter.viewIfLoaded?.window != nil else {
            onResult(GoogleAuthResultFailure(code: "GOOGLE_NOT_READY")); return
        }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            onResult(GoogleAuthResultFailure(code: "GOOGLE_UNAVAILABLE")); return
        }
        let nonce = Data(bytes).base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        generation += 1
        let attempt = generation
        pending = onResult
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID, serverClientID: serverClientID)
        GIDSignIn.sharedInstance.signIn(withPresenting: presenter, hint: nil, additionalScopes: nil, nonce: nonce) { [weak self] result, error in
            guard let self, self.generation == attempt, let callback = self.pending else {
                GIDSignIn.sharedInstance.signOut(); return
            }
            self.pending = nil
            // Solo la sesión Firebase de BPM se conserva; se retira la sesión adicional del SDK.
            defer { GIDSignIn.sharedInstance.signOut() }
            if let error = error as NSError? {
                let code = error.domain == kGIDSignInErrorDomain && error.code == GIDSignInError.canceled.rawValue
                    ? "GOOGLE_CANCELLED" : "GOOGLE_UNAVAILABLE"
                callback(GoogleAuthResultFailure(code: code))
            } else if let token = result?.user.idToken?.tokenString, !token.isEmpty {
                callback(GoogleAuthResultFederated(idToken: token, nonce: nonce))
            } else {
                callback(GoogleAuthResultFailure(code: "INVALID_RESPONSE"))
            }
        }
    }

    func clear() {
        DispatchQueue.main.async { [self] in
            generation += 1
            let callback = pending
            pending = nil
            GIDSignIn.sharedInstance.signOut()
            callback?(GoogleAuthResultFailure(code: "GOOGLE_CANCELLED"))
        }
    }
}
