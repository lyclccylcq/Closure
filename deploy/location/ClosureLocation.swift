import AppKit
import CoreLocation
import Foundation

// One request per launch. Only a city-level name is returned, never an address.
final class LocationProbe: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let geocoder = CLGeocoder()
    private var finished = false
    private var requested = false
    private var timer: Timer?
    private let output: String?

    init(output: String?) {
        self.output = output
        super.init()
    }

    func start() {
        guard CLLocationManager.locationServicesEnabled() else {
            finishError("SERVICES_DISABLED")
            return
        }
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
        timer = Timer.scheduledTimer(withTimeInterval: 40, repeats: false) { [weak self] _ in
            self?.finishError("TIMEOUT")
        }
        requestIfAllowed()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        requestIfAllowed()
    }

    private func requestIfAllowed() {
        guard !finished else { return }
        switch manager.authorizationStatus {
        case .denied:
            finishError("DENIED")
        case .restricted:
            finishError("RESTRICTED")
        case .notDetermined, .authorizedAlways, .authorizedWhenInUse:
            if !requested {
                requested = true
                // On macOS starting the service presents the system's own
                // permission dialog. No permission database is read or changed.
                manager.requestLocation()
            }
        @unknown default:
            finishError("LOCATION_UNAVAILABLE")
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard !finished, let location = locations.last,
              location.horizontalAccuracy >= 0,
              abs(location.timestamp.timeIntervalSinceNow) <= 300,
              CLLocationCoordinate2DIsValid(location.coordinate) else {
            finishError("LOCATION_UNAVAILABLE")
            return
        }
        manager.stopUpdatingLocation()
        geocoder.reverseGeocodeLocation(location, preferredLocale: Locale(identifier: "zh_CN")) { [weak self] places, _ in
            DispatchQueue.main.async {
                guard let self = self, !self.finished else { return }
                let place = places?.first
                let city = place?.locality ?? place?.subAdministrativeArea ?? place?.administrativeArea ?? "当前位置"
                let encoded = Data(city.utf8).base64EncodedString()
                self.finish("CLOSURE_LOCATION_V1\t\(location.coordinate.latitude)\t\(location.coordinate.longitude)\t\(encoded)\n", code: 0)
            }
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finishError(manager.authorizationStatus == .denied ? "DENIED" : "LOCATION_UNAVAILABLE")
    }

    private func finishError(_ reason: String) {
        finish("CLOSURE_LOCATION_ERROR\t\(reason)\n", code: 1)
    }

    private func finish(_ line: String, code: Int32) {
        guard !finished else { return }
        finished = true
        timer?.invalidate()
        manager.stopUpdatingLocation()
        geocoder.cancelGeocode()
        let data = Data(line.utf8)
        if let output = output {
            // Java creates this file in an owner-only temporary directory.
            // Reuse that file rather than create an atomic replacement with
            // different permissions. The caller deletes it after reading.
            do {
                let handle = try FileHandle(forWritingTo: URL(fileURLWithPath: output))
                try handle.truncate(atOffset: 0)
                try handle.write(contentsOf: data)
                try handle.close()
            } catch {
                exit(2)
            }
        } else {
            FileHandle.standardOutput.write(data)
        }
        exit(code)
    }
}

if CommandLine.arguments.contains("--status") {
    let manager = CLLocationManager()
    print("CLOSURE_LOCATION_STATUS\t\(manager.authorizationStatus.rawValue)")
    exit(0)
}
let arguments = CommandLine.arguments
var output: String?
if let index = arguments.firstIndex(of: "--output"), index + 1 < arguments.count {
    output = arguments[index + 1]
}
let application = NSApplication.shared
application.setActivationPolicy(.accessory)
let probe = LocationProbe(output: output)
probe.start()
application.run()
