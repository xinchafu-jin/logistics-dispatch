import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, map, of, timeout } from 'rxjs';

export interface CurrentWeather {
  city: string;
  condition: string;
  icon: string;
  temperature: number;
}

interface OpenMeteoResponse {
  current: {
    temperature_2m: number;
    weather_code: number;
  };
}

const TAINAN_WEATHER_URL =
  'https://api.open-meteo.com/v1/forecast?latitude=22.9999&longitude=120.227&current=temperature_2m,weather_code&timezone=Asia%2FTaipei';

const FALLBACK_WEATHER: CurrentWeather = {
  city: '台南',
  condition: '今日配送天氣',
  icon: String.fromCodePoint(0x2601, 0xfe0e),
  temperature: 24,
};

@Injectable({ providedIn: 'root' })
export class WeatherService {
  private readonly http = inject(HttpClient);

  getCurrentWeather() {
    return this.http.get<OpenMeteoResponse>(TAINAN_WEATHER_URL).pipe(
      timeout(5000),
      map(({ current }) => ({
        city: '台南',
        temperature: Math.round(current.temperature_2m),
        ...this.describeWeather(current.weather_code),
      })),
      catchError(() => of(FALLBACK_WEATHER)),
    );
  }

  private describeWeather(weatherCode: number): Pick<CurrentWeather, 'condition' | 'icon'> {
    if (weatherCode === 0) {
      return { condition: '晴朗', icon: String.fromCodePoint(0x2600, 0xfe0e) };
    }

    if ([1, 2, 3].includes(weatherCode)) {
      return { condition: '多雲', icon: String.fromCodePoint(0x2601, 0xfe0e) };
    }

    if ([45, 48].includes(weatherCode)) {
      return { condition: '有霧', icon: String.fromCodePoint(0x2261, 0xfe0e) };
    }

    if ((weatherCode >= 51 && weatherCode <= 67) || (weatherCode >= 80 && weatherCode <= 82)) {
      return { condition: '降雨', icon: String.fromCodePoint(0x2602, 0xfe0e) };
    }

    if ((weatherCode >= 71 && weatherCode <= 77) || (weatherCode >= 85 && weatherCode <= 86)) {
      return { condition: '降雪', icon: String.fromCodePoint(0x2744, 0xfe0e) };
    }

    if (weatherCode >= 95) {
      return { condition: '雷雨', icon: String.fromCodePoint(0x26a1, 0xfe0e) };
    }

    return FALLBACK_WEATHER;
  }
}
