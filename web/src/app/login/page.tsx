"use client";

import { useActionState, Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { login } from "./actions";

/**
 * The login form component that needs Suspense because it uses useSearchParams.
 */
function LoginForm() {
  const searchParams = useSearchParams();
  const from = searchParams.get("from") || "/";

  const [state, formAction, isPending] = useActionState(login, null);

  return (
    <form action={formAction} className="mt-8 space-y-6">
      <input type="hidden" name="from" value={from} />

      <div className="space-y-4">
        <div className="relative group">
          <input
            id="password"
            name="password"
            type="password"
            required
            disabled={isPending}
            placeholder="Master Key"
            className="w-full px-5 py-4 bg-[#131926] border border-white/5 rounded-2xl focus:outline-none focus:border-[#4EC9E8]/50 focus:ring-1 focus:ring-[#4EC9E8]/50 transition-all placeholder:text-[#6E7889] text-lg font-medium tracking-widest disabled:opacity-50"
          />
        </div>

        {/* role="alert" so a screen reader hears the rejection: this was a styled
            paragraph, silent to anyone not looking at it. The pulse is dropped under
            prefers-reduced-motion, which the rest of the app already respects. */}
        {state?.error && (
          <p
            role="alert"
            className="text-[#FF453A] text-sm text-center font-bold motion-safe:animate-pulse"
          >
            {state.error}
          </p>
        )}
      </div>

      <button
        type="submit"
        disabled={isPending}
        className="w-full py-4 px-6 bg-[#0A84FF] hover:bg-[#4CC2FF] active:scale-[0.98] text-white font-bold rounded-2xl shadow-lg shadow-blue-500/20 transition-all flex items-center justify-center gap-2 disabled:opacity-50 disabled:active:scale-100"
      >
        {isPending ? (
          <span className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
        ) : (
          <>
            Unlock
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M14 5l7 7m0 0l-7 7m7-7H3" />
            </svg>
          </>
        )}
      </button>
    </form>
  );
}

/**
 * Personal Access Gate
 *
 * A minimal, premium login screen that guards the entire application.
 * Matches the 'Obsidian' theme of DeutschFlow.
 */
export default function LoginPage() {
  return (
    <main className="min-h-screen flex items-center justify-center bg-[#0A0E16] p-4 font-inter text-[#F2F5FA]">
      <div className="w-full max-w-md space-y-8 animate-in fade-in zoom-in duration-500">
        <div className="text-center space-y-2">
          <div className="inline-block p-3 rounded-2xl bg-[#131926] border border-white/5 shadow-2xl mb-4">
            <svg
              className="w-10 h-10 text-[#4EC9E8]"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
              />
            </svg>
          </div>
          <h1 className="text-3xl font-black tracking-tight tracking-tighter uppercase">
            DeutschFlow
          </h1>
          <p className="text-[#98A2B3] text-sm">
            This is a private instance. Enter your security key to proceed.
          </p>
        </div>

        <Suspense fallback={
          <div className="flex justify-center py-12">
            <div className="w-8 h-8 border-2 border-[#4EC9E8]/30 border-t-[#4EC9E8] rounded-full animate-spin" />
          </div>
        }>
          <LoginForm />
        </Suspense>

        <div className="text-center pt-8">
          <p className="text-[#6E7889] text-[10px] uppercase tracking-widest">
            DeutschFlow Obsidian Edition
          </p>
        </div>
      </div>
    </main>
  );
}
