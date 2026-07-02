"use client"

import { Toaster as Sonner, type ToasterProps } from "sonner"
import { CircleCheckIcon, InfoIcon, TriangleAlertIcon, OctagonXIcon, Loader2Icon } from "lucide-react"

// This app has no dark-mode toggle — the UI always renders in light mode.
// Pinning the toaster to "light" keeps its text/background colors in sync
// with the page. Reading system-preference via useTheme() here previously
// caused white-on-white text when the OS preferred dark mode.
const Toaster = ({ ...props }: ToasterProps) => {
  return (
    <Sonner
      theme="light"
      className="toaster group"
      icons={{
        success: (
          <CircleCheckIcon className="size-4" />
        ),
        info: (
          <InfoIcon className="size-4" />
        ),
        warning: (
          <TriangleAlertIcon className="size-4" />
        ),
        error: (
          <OctagonXIcon className="size-4" />
        ),
        loading: (
          <Loader2Icon className="size-4 animate-spin" />
        ),
      }}
      style={
        {
          "--normal-bg": "var(--popover)",
          "--normal-text": "var(--popover-foreground)",
          "--normal-border": "var(--border)",
          "--border-radius": "var(--radius)",
        } as React.CSSProperties
      }
      toastOptions={{
        classNames: {
          toast: "cn-toast",
          success: "!bg-emerald-50 !text-emerald-900 !border-emerald-200 [&_[data-icon]]:!text-emerald-600",
          warning: "!bg-amber-50 !text-amber-900 !border-amber-200 [&_[data-icon]]:!text-amber-600",
          error: "!bg-red-50 !text-red-900 !border-red-200 [&_[data-icon]]:!text-red-600",
        },
      }}
      {...props}
    />
  )
}

export { Toaster }
