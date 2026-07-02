"use client";

import { useCallback, useEffect, useState } from "react";
import Image from "next/image";

import { AccountsOverview } from "@/components/dashboard/accounts-overview";
import { StatusChart } from "@/components/dashboard/status-chart";
import { TransferForm } from "@/components/dashboard/transfer-form";
import { TransactionsTable } from "@/components/dashboard/transactions-table";
import {
  ApiRequestError,
  listAccounts,
  listTransactions,
  transactionAnalytics,
} from "@/lib/api";
import type { Account, PageResponse, StatusSummary, Transaction, TransactionStatus } from "@/lib/types";

const POLL_INTERVAL_MS = 8000;

export default function Home() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [accountsLoading, setAccountsLoading] = useState(true);

  const [transactionsPage, setTransactionsPage] = useState<PageResponse<Transaction> | null>(null);
  const [transactionsLoading, setTransactionsLoading] = useState(true);
  const [statusFilter, setStatusFilter] = useState<TransactionStatus | "ALL">("ALL");
  const [page, setPage] = useState(0);

  const [summary, setSummary] = useState<StatusSummary[]>([]);

  const [apiUnreachable, setApiUnreachable] = useState(false);

  const refreshAccounts = useCallback(async () => {
    try {
      const data = await listAccounts();
      setAccounts(data);
      setApiUnreachable(false);
    } catch (error) {
      if (error instanceof ApiRequestError) setApiUnreachable(false);
      else setApiUnreachable(true);
    } finally {
      setAccountsLoading(false);
    }
  }, []);

  const refreshTransactions = useCallback(async () => {
    try {
      const data = await listTransactions({
        status: statusFilter === "ALL" ? undefined : statusFilter,
        page,
      });
      setTransactionsPage(data);
    } catch {
      // surfaced via apiUnreachable from refreshAccounts
    } finally {
      setTransactionsLoading(false);
    }
  }, [statusFilter, page]);

  const refreshSummary = useCallback(async () => {
    try {
      setSummary(await transactionAnalytics());
    } catch {
      // surfaced via apiUnreachable from refreshAccounts
    }
  }, []);

  const refreshAll = useCallback(() => {
    void refreshAccounts();
    void refreshTransactions();
    void refreshSummary();
  }, [refreshAccounts, refreshTransactions, refreshSummary]);

  useEffect(() => {
    refreshAll();
    const interval = setInterval(refreshAll, POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [refreshAll]);

  return (
    <div className="mx-auto flex max-w-6xl flex-1 flex-col gap-8 px-6 py-10">
      <header className="space-y-1">
        <Image
          src="/brand/datakite-logo.png"
          alt="DataKite"
          width={580}
          height={104}
          priority
          className="h-6 w-auto dark:hidden"
        />
        <Image
          src="/brand/datakite-logo-dark.png"
          alt="DataKite"
          width={580}
          height={104}
          priority
          className="hidden h-6 w-auto dark:block"
        />
        <h1 className="text-2xl font-semibold tracking-tight">Concurrency Ledger</h1>
        <p className="text-sm text-muted-foreground">
          High-concurrency, idempotent transfer ledger with fraud review.
        </p>
        {apiUnreachable && (
          <p className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
            Could not reach the ledger API. Is the backend running at{" "}
            {process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/api/v1/ledger"}?
          </p>
        )}
      </header>

      <div className="grid gap-6 lg:grid-cols-5">
        <AccountsOverview
          accounts={accounts}
          loading={accountsLoading}
          className="lg:col-span-3"
        />
        <StatusChart data={summary} className="lg:col-span-2" />
      </div>

      <TransferForm accounts={accounts} onTransferred={refreshAll} />

      <TransactionsTable
        page={transactionsPage}
        accounts={accounts}
        loading={transactionsLoading}
        statusFilter={statusFilter}
        onStatusFilterChange={(status) => {
          setStatusFilter(status);
          setPage(0);
        }}
        onPageChange={setPage}
        onChanged={refreshAll}
      />
    </div>
  );
}
