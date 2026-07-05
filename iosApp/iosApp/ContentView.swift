import SwiftUI
import Shared

/// Hosts the Kotlin Multiplatform + Compose Multiplatform UI tree defined in
/// `shared/src/commonMain/.../App.kt`, exposed via `MainViewController()`.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
    }
}
