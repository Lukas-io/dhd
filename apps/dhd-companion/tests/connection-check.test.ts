import type { AddressInfo } from "node:net";

import { afterEach, describe, expect, it, vi } from "vitest";

const requestBridgeMock = vi.hoisted(() => vi.fn());

vi.mock("../src/phone-assistant-bridge.js", async () => {
  const actual = await vi.importActual<typeof import("../src/phone-assistant-bridge.js")>(
    "../src/phone-assistant-bridge.js"
  );
  return { ...actual, requestBridge: requestBridgeMock };
});

const { createCompanionWebServer } = await import("../src/companion-web/server.js");

const openServers: ReturnType<typeof createCompanionWebServer>[] = [];

afterEach(async () => {
  requestBridgeMock.mockReset();
  await Promise.all(
    openServers.splice(0).map(
      (server) =>
        new Promise<void>((resolve) => {
          if (!server.listening) {
            resolve();
            return;
          }
          server.close(() => resolve());
        })
    )
  );
});

async function openWebServer(): Promise<string> {
  const server = createCompanionWebServer();
  openServers.push(server);
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve());
  });
  const address = server.address() as AddressInfo;
  return `http://127.0.0.1:${address.port}`;
}

function connectedStatus() {
  return {
    type: "status",
    ok: true,
    state: "idle",
    active: false,
  };
}

describe("companion phone-link checks", () => {
  it("shares one in-flight probe across simultaneous callers", async () => {
    let calls = 0;
    requestBridgeMock.mockImplementation(async () => {
      calls += 1;
      await new Promise((resolve) => setTimeout(resolve, 25));
      return connectedStatus();
    });

    const baseUrl = await openWebServer();
    const [first, second] = await Promise.all([
      fetch(`${baseUrl}/api/check`, { method: "POST" }),
      fetch(`${baseUrl}/api/check`, { method: "POST" }),
    ]);

    expect(first.ok).toBe(true);
    expect(second.ok).toBe(true);
    expect(await first.json()).toMatchObject({ ok: true });
    expect(await second.json()).toMatchObject({ ok: true });
    expect(calls).toBe(1);
  });

  it("retries a dropped status probe on a fresh bridge request", async () => {
    requestBridgeMock
      .mockRejectedValueOnce(new Error("Could not connect to the phone assistant bridge."))
      .mockResolvedValueOnce(connectedStatus());

    const baseUrl = await openWebServer();
    const response = await fetch(`${baseUrl}/api/check`, { method: "POST" });

    expect(response.ok).toBe(true);
    expect(await response.json()).toMatchObject({ ok: true });
    expect(requestBridgeMock).toHaveBeenCalledTimes(2);
  });
});
