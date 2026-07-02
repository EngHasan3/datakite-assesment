import type {
  Account,
  PageResponse,
  StatusSummary,
  Transaction,
  TransactionStatus,
  TransferRequest,
} from "./types";

const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/api/v1/ledger";

export class ApiRequestError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T>(
  path: string,
  init?: RequestInit
): Promise<{ data: T; headers: Headers }> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...init?.headers,
    },
  });

  const contentType = response.headers.get("content-type") ?? "";
  const body = contentType.includes("application/json")
    ? await response.json()
    : null;

  if (!response.ok) {
    const message =
      (body as { message?: string } | null)?.message ??
      `Request failed with status ${response.status}`;
    throw new ApiRequestError(response.status, message);
  }

  return { data: body as T, headers: response.headers };
}

export async function listAccounts(): Promise<Account[]> {
  const { data } = await request<Account[]>("/accounts");
  return data;
}

export async function listTransactions(params: {
  status?: TransactionStatus;
  page?: number;
  size?: number;
} = {}): Promise<PageResponse<Transaction>> {
  const search = new URLSearchParams();
  if (params.status) search.set("status", params.status);
  search.set("page", String(params.page ?? 0));
  search.set("size", String(params.size ?? 8));
  const { data } = await request<PageResponse<Transaction>>(
    `/transactions?${search.toString()}`
  );
  return data;
}

export async function transactionAnalytics(): Promise<StatusSummary[]> {
  const { data } = await request<StatusSummary[]>(
    "/transactions/analytics/summary"
  );
  return data;
}

export async function transfer(
  body: TransferRequest,
  idempotencyKey: string
): Promise<{ transaction: Transaction; replay: boolean }> {
  const { data, headers } = await request<Transaction>("/transfer", {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify(body),
  });
  return { transaction: data, replay: headers.get("Idempotent-Replay") === "true" };
}

export async function releaseTransaction(id: string): Promise<Transaction> {
  const { data } = await request<Transaction>(
    `/admin/transactions/${id}/release`,
    { method: "POST" }
  );
  return data;
}

export async function rejectTransaction(id: string): Promise<Transaction> {
  const { data } = await request<Transaction>(
    `/admin/transactions/${id}/reject`,
    { method: "POST" }
  );
  return data;
}
