export type TransactionStatus = "COMPLETED" | "PENDING_REVIEW" | "REJECTED" | "FAILED";

export interface Account {
  id: string;
  accountNumber: string;
  balance: number;
}

export interface Transaction {
  id: string;
  sourceAccountId: string;
  destinationAccountId: string;
  amount: number;
  currency: string;
  date: string;
  status: TransactionStatus;
  idempotencyKey: string;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface StatusSummary {
  status: TransactionStatus;
  count: number;
  totalAmount: number;
}

export interface TransferRequest {
  sourceAccountNumber: string;
  destinationAccountNumber: string;
  amount: number;
  currency: string;
}
