"use client";

import { useId, type InputHTMLAttributes, type ReactNode } from "react";
import { SearchIcon } from "@/components/icons";

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
}: {
  placeholder: string;
  value: string;
  onChange: (value: string) => void;
  trailingIcon?: ReactNode;
}) {
  return (
    <GlassTextField
      value={value}
      onChange={(event) => onChange(event.target.value)}
      placeholder={placeholder}
      leadingIcon={<SearchIcon className="size-5 text-azure-deep transition-colors group-focus-within:text-azure-glow" />}
      trailingIcon={trailingIcon}
      type="search"
    />
  );
}
