import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { forkJoin } from 'rxjs';
import { DispatchApiService } from './dispatch-api.service';

describe('DispatchApiService', () => {
  let service: DispatchApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });

    service = TestBed.inject(DispatchApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('uses the direct list endpoints exposed by every backend controller', () => {
    forkJoin({
      drivers: service.getDrivers(),
      vehicles: service.getVehicles(),
      stores: service.getStores(),
      warehouses: service.getWarehouses(),
      orders: service.getOrders(),
    }).subscribe((result) => {
      expect(result).toEqual({
        drivers: [],
        vehicles: [],
        stores: [],
        warehouses: [],
        orders: [],
      });
    });

    for (const endpoint of [
      '/api/drivers',
      '/api/vehicles',
      '/api/stores',
      '/api/warehouses',
      '/api/orders',
    ]) {
      const request = httpTesting.expectOne(endpoint);
      expect(request.request.method).toBe('GET');
      request.flush([]);
    }
  });

  it('writes order status using the backend PUT contract', () => {
    service
      .updateOrder(12, {
        orderNumber: 'DO-001',
        storeId: 3,
        boxCount: 4,
        notes: '',
        deliveryDate: '2026-08-19',
        status: 'CONFIRMED',
      })
      .subscribe();

    const request = httpTesting.expectOne('/api/orders/12');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.status).toBe('CONFIRMED');
    request.flush({
      id: 12,
      orderNumber: 'DO-001',
      storeId: 3,
      boxCount: 4,
      notes: '',
      deliveryDate: '2026-08-19',
      status: 'CONFIRMED',
    });
  });
});
