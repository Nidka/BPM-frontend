import SwiftUI
import ComposeApp
import GoogleSignIn

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let driver = GoogleSignInDriver()
        let controller = MainViewControllerKt.MainViewController(googleAuthProvider: IosGoogleAuthProvider(driver: driver))
        driver.attach(controller)
        return controller
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct BPMApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea(.keyboard)
                .onOpenURL { url in _ = GIDSignIn.sharedInstance.handle(url) }
        }
    }
}
