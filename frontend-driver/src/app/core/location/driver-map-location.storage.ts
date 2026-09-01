export interface StoredMapLocation {
  lat: number;
  lng: number;
}

const MAP_LOCATION_STORAGE_KEY = 'logistics-dispatch.driver-map-location';

export function readStoredMapLocation(): StoredMapLocation | null {
  if (typeof sessionStorage === 'undefined') {
    return null;
  }

  try {
    const stored = JSON.parse(sessionStorage.getItem(MAP_LOCATION_STORAGE_KEY) ?? 'null') as StoredMapLocation | null;
    if (!stored || !Number.isFinite(stored.lat) || !Number.isFinite(stored.lng)) {
      return null;
    }

    return stored;
  } catch {
    return null;
  }
}

export function saveStoredMapLocation(location: StoredMapLocation): void {
  if (typeof sessionStorage !== 'undefined') {
    sessionStorage.setItem(MAP_LOCATION_STORAGE_KEY, JSON.stringify(location));
  }
}

export function clearStoredMapLocation(): void {
  if (typeof sessionStorage !== 'undefined') {
    sessionStorage.removeItem(MAP_LOCATION_STORAGE_KEY);
  }
}
