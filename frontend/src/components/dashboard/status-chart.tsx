"use client";

import { Bar, BarChart, CartesianGrid, Cell, XAxis } from "recharts";

import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  ChartConfig,
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
} from "@/components/ui/chart";
import { cn } from "@/lib/utils";
import type { StatusSummary } from "@/lib/types";

const chartConfig: ChartConfig = {
  count: {
    label: "Transactions",
    color: "var(--primary)",
  },
};

const STATUS_LABELS: Record<string, string> = {
  COMPLETED: "Completed",
  PENDING_REVIEW: "Pending review",
  REJECTED: "Rejected",
  FAILED: "Failed",
};

const STATUS_COLORS: Record<string, string> = {
  COMPLETED: "#10b981",
  PENDING_REVIEW: "#f59e0b",
  REJECTED: "#ef4444",
  FAILED: "#71717a",
};

export function StatusChart({
  data,
  className,
}: {
  data: StatusSummary[];
  className?: string;
}) {
  const chartData = data.map((item) => ({
    status: STATUS_LABELS[item.status] ?? item.status,
    count: item.count,
    fill: STATUS_COLORS[item.status] ?? "var(--color-count)",
  }));

  return (
    <Card className={cn(className)}>
      <CardHeader>
        <CardTitle>Volume by status</CardTitle>
        <CardDescription>Transaction count grouped by outcome</CardDescription>
      </CardHeader>
      <CardContent>
        {chartData.length === 0 ? (
          <p className="text-sm text-muted-foreground">No transactions yet.</p>
        ) : (
          <ChartContainer config={chartConfig} className="aspect-auto h-56 w-full">
            <BarChart data={chartData}>
              <CartesianGrid vertical={false} />
              <XAxis
                dataKey="status"
                tickLine={false}
                axisLine={false}
                tickMargin={8}
                fontSize={11}
              />
              <ChartTooltip content={<ChartTooltipContent hideLabel={false} />} />
              <Bar dataKey="count" radius={4}>
                {chartData.map((entry) => (
                  <Cell key={entry.status} fill={entry.fill} />
                ))}
              </Bar>
            </BarChart>
          </ChartContainer>
        )}
      </CardContent>
    </Card>
  );
}
