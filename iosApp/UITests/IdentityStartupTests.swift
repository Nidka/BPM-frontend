import XCTest

/// Comprueba el arranque real y la disponibilidad de Google sin autenticar cuentas.
final class IdentityStartupTests: XCTestCase {
    @MainActor
    func testFreshLoginStartsWithoutStorageErrorsAndOffersGoogle() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["Bienvenido de nuevo"].waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["Reintentar sesión guardada"].exists)
        let google = app.buttons["Continuar con Google"]
        for _ in 0..<4 where !google.isHittable { app.swipeUp() }
        XCTAssertTrue(google.exists)
        XCTAssertTrue(google.isEnabled)
        XCTAssertTrue(google.isHittable)
    }
}
