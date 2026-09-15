import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { map, timeout } from 'rxjs';

export interface DriverWeather {
  city: string;
  condition: string;
  icon: string;
  temperature: number;
  updatedAt: string;
}

interface OpenMeteoResponse {
  current: {
    temperature_2m: number;
    time: string;
    weather_code: number;
  };
}

const KAOHSIUNG_WEATHER_URL =
  'https://api.open-meteo.com/v1/forecast?latitude=22.6273&longitude=120.3014&current=temperature_2m,weather_code&timezone=Asia%2FTaipei';

@Injectable({ providedIn: 'root' })
export class DriverWeatherService {
  private readonly http = inject(HttpClient);

  getCurrentWeather() {
    return this.http.get<OpenMeteoResponse>(KAOHSIUNG_WEATHER_URL).pipe(
      timeout(5000),
      map(({ current }) => ({
        city: '高雄',
        temperature: Math.round(current.temperature_2m),
        updatedAt: current.time.slice(11, 16),
        ...this.describeWeather(current.weather_code),
      })),
    );
  }

  private describeWeather(weatherCode: number): Pick<DriverWeather, 'condition' | 'icon'> {
    if (weatherCode === 0) {
      return { condition: '晴朗', icon: '晴' };
    }

    if ([1, 2, 3].includes(weatherCode)) {
      return { condition: '多雲', icon: '雲' };
    }

    if ([45, 48].includes(weatherCode)) {
      return { condition: '有霧', icon: '霧' };
    }

    if ((weatherCode >= 51 && weatherCode <= 67) || (weatherCode >= 80 && weatherCode <= 82)) {
      return { condition: '降雨', icon: '雨' };
    }

    if ((weatherCode >= 71 && weatherCode <= 77) || (weatherCode >= 85 && weatherCode <= 86)) {
      return { condition: '降雪', icon: '雪' };
    }

    if (weatherCode >= 95) {
      return { condition: '雷雨', icon: '雷' };
    }

    return { condition: '天氣狀況未知', icon: '...' };
  }
}
