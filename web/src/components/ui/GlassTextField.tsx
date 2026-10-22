"use client";

import { useId, type InputHTMLAttributes, type ReactNode } from "react";
import { CloseIcon, SearchIcon } from "@/components/icons";
import { useI18n } from "@/hooks/useI18n";

/**
 * GlassTextField — the one input container in the app.
 *
 * Compose equivalent: GlassComponents.kt `GlassTextField`. The glass-input
 * utility owns the fill, the 24px corner and the edge, which is AzureDeep at
 * rest and AzureGlow while focused (handled by `.glass-input:focus-within`).
 */
interface GlassTextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "label"> {
  label?: string;
  leadingIcon?: ReactNode;
  trailingIcon?: ReactNode;
}

export function GlassTextField({
  label,
  leadingIcon,
  trailingIcon,
  placeholder,
  className = "",
  ...rest
}: GlassTextFieldProps) {
  const id = useId();
  return (
    <div className={`w-full ${className}`}>
      {label != null && (
        <label
          htmlFor={id}
          className="mb-2.5 block pl-1 text-sm font-medium text-on-surface-variant"
        >
          {label}
        </label>
      )}
      <div className="glass-input group flex items-center gap-3 px-5">
        {leadingIcon}
        <input
          id={id}
          placeholder={placeholder}
          className="h-14 w-full bg-transparent text-base text-on-surface placeholder:text-on-surface-variant focus:outline-none"
          {...rest}
        />
        {trailingIcon}
      </div>
    </div>
  );
}

/** A GlassTextField fixed to the search role — History and Library share it. */
export function SearchInput({
  placeholder,
  value,
  onChange,
  trailingIcon,
  ariaLabel,
}: {
  placeholder: string;
  value: string;
  onChange: (value: string) => void;
  trailingIcon?: ReactNode;
  ariaLabel?: string;
}) {
  const { t } = useI18n();
  const clearButton = value ? (
    <button
      type="button"
      onClick={() => onChange("")}
      aria-label={t("action.clearFilter")}
      className="flex size-7 items-center justify-center rounded-full text-on-surface-variant hover:text-on-surface hover:bg-surface-variant/40 transition-colors focus-visible:outline-2 focus-visible:outline-azure-glow"
    >
      <CloseIcon className="size-4" />
    </button>
  ) : null;

  return (
    <GlassTextField
      value={value}
      onChange={(event) => onChange(event.target.value)}
      placeholder={placeholder}
      aria-label={ariaLabel || t("action.search")}
      leadingIcon={<SearchIcon className="size-5 text-azure-deep transition-colors group-focus-within:text-azure-glow" />}
      trailingIcon={trailingIcon ?? clearButton}
      type="search"
    />
  );
}
