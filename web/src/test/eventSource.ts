// A stand-in for EventSource, which jsdom lacks (mocked, not live). Tests drive it: open the
// stream, push events with ids, and fail it the way a dropped connection does. The real
// browser behaviour (automatic reconnect with Last-Event-ID) is covered by the Playwright E2E.

type Listener = (event: MessageEvent<string>) => void;

export class FakeEventSource {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;
  static instances: FakeEventSource[] = [];

  readonly url: string;
  readyState = FakeEventSource.CONNECTING;
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  private readonly listeners = new Map<string, Set<Listener>>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: Listener) {
    const set = this.listeners.get(type) ?? new Set<Listener>();
    set.add(listener);
    this.listeners.set(type, set);
  }

  removeEventListener(type: string, listener: Listener) {
    this.listeners.get(type)?.delete(listener);
  }

  close() {
    this.readyState = FakeEventSource.CLOSED;
  }

  get closed() {
    return this.readyState === FakeEventSource.CLOSED;
  }

  // --- test controls ---

  open() {
    this.readyState = FakeEventSource.OPEN;
    this.onopen?.(new Event('open'));
  }

  emit(type: string, id: number | string, data: unknown = {}) {
    const event = new MessageEvent<string>(type, {
      data: typeof data === 'string' ? data : JSON.stringify(data),
      lastEventId: String(id),
    });
    this.listeners.get(type)?.forEach((listener) => listener(event));
  }

  /** A dropped connection (EventSource then retries) or, with `closed`, a refused one. */
  fail(closed = false) {
    this.readyState = closed ? FakeEventSource.CLOSED : FakeEventSource.CONNECTING;
    this.onerror?.(new Event('error'));
  }

  static latest(): FakeEventSource {
    const last = FakeEventSource.instances.at(-1);
    if (!last) throw new Error('no EventSource was opened');
    return last;
  }

  static reset() {
    FakeEventSource.instances = [];
  }
}
