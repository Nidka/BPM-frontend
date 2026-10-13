import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct BPMApp: App {
    var body: some Scene {
        WindowGroup { ComposeView().ignoresSafeArea(.keyboard) }
    }
}
