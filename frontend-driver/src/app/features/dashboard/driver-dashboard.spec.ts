import { findNearest } from './driver-dashboard';

describe('findNearest', () => {
  // 台南往北的三個點，間隔約 3 公里
  const route = [
    [120.2255, 22.9908] as [number, number],
    [120.2578, 23.0264] as [number, number],
    [120.2954, 23.0781] as [number, number],
  ];

  it('剛好站在某個路線點上時，距離接近 0、索引正確', () => {
    const result = findNearest([120.2578, 23.0264], route);

    expect(result.index).toBe(1);
    expect(result.distance).toBeLessThan(1);
  });

  it('偏離路線時回報實際距離', () => {
    // 往東偏 0.1 度，約 10 公里
    const result = findNearest([120.3578, 23.0264], route);

    expect(result.distance).toBeGreaterThan(5000);
  });

  it('在起點附近時索引是 0', () => {
    const result = findNearest([120.2256, 22.9910], route);

    expect(result.index).toBe(0);
  });

  it('空路線回傳 Infinity，不會爆', () => {
    const result = findNearest([120.22, 22.99], []);

    expect(result.distance).toBe(Infinity);
    expect(result.index).toBe(0);
  });
});
