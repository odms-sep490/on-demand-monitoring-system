"""Local Open-Meteo proxy for simulated flights."""

import argparse
from datetime import date, datetime, time, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from urllib.error import URLError
from urllib.parse import parse_qs, urlsplit
from urllib.request import urlopen


SAMPLE = {
    "temperature_2m": 28,
    "relative_humidity_2m": 65,
    "precipitation_probability": 0,
    "precipitation": 0,
    "weather_code": 0,
    "cloud_cover": 10,
    "visibility": 10000,
    "wind_speed_10m": 10,
    "wind_gusts_10m": 15,
}


def simulated_forecast(day):
    start = datetime.combine(day, time.min)
    hourly = {
        "time": [
            (start + timedelta(hours=hour)).isoformat(timespec="minutes")
            for hour in range(24)
        ]
    }
    hourly.update({field: [value] * 24 for field, value in SAMPLE.items()})
    return json.dumps({"hourly": hourly}).encode("utf-8")


class WeatherHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        request = urlsplit(self.path)
        if request.path != "/v1/forecast":
            self.send_error(404)
            return
        try:
            day = date.fromisoformat(
                parse_qs(request.query).get("start_date", [""])[0]
            )
        except ValueError:
            self.send_error(400, "Expected start_date in YYYY-MM-DD format")
            return

        source = "open-meteo"
        try:
            if self.server.offline:
                raise OSError("offline simulation")
            with urlopen(
                "https://api.open-meteo.com/v1/forecast?" + request.query,
                timeout=2,
            ) as upstream:
                body = upstream.read()
            hourly = json.loads(body)["hourly"]
            if not isinstance(hourly["time"], list) or not hourly["time"]:
                raise ValueError("missing hourly forecast")
        except (URLError, OSError, ValueError, KeyError, TypeError) as error:
            self.log_error("Serving simulated weather: %s", error)
            source = "simulation"
            body = simulated_forecast(day)

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-Weather-Source", source)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--offline", action="store_true", help="Use simulation data only")
    args = parser.parse_args()
    with ThreadingHTTPServer(("127.0.0.1", args.port), WeatherHandler) as server:
        server.offline = args.offline
        server.serve_forever()


if __name__ == "__main__":
    main()
