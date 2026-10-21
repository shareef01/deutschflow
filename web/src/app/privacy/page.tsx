import type { Metadata } from "next";

/**
 * The privacy policy, public on purpose: Google Play requires a policy URL that
 * anyone can open, and the Android app links here from Settings (AUDIT_2026-10-12
 * F-11). It sits outside the (app) group and is listed in the proxy's PUBLIC_PATHS,
 * so it renders without the access gate or the app shell.
 *
 * Every statement is meant to describe what the code does today. When behaviour
 * changes - a new network call, a new stored field, a backup rule - change this page
 * in the same commit, and bump LAST_UPDATED.
 */

export const metadata: Metadata = {
  title: "Privacy Policy · DeutschFlow",
  description: "What DeutschFlow stores, what it sends, and to whom.",
};

const LAST_UPDATED = "21 October 2026";

/**
 * Where to send privacy questions. Left for the owner to fill in: a policy needs a
 * real contact, and this repository should not invent one.
 */
const CONTACT: string | null = null;

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="mt-8">
      <h2 className="text-title-large font-bold text-on-surface">{title}</h2>
      <div className="mt-3 space-y-3 text-body-large text-on-surface-variant">{children}</div>
    </section>
  );
}

export default function PrivacyPage() {
  return (
    <main className="mx-auto w-full max-w-2xl px-4 py-10 sm:px-6">
      <h1 className="text-headline-medium font-bold text-on-surface">Privacy Policy</h1>
      <p className="mt-2 text-body-medium text-on-surface-variant">
        DeutschFlow for Android and the web · Last updated {LAST_UPDATED}
      </p>

      <Section title="In short">
        <p>
          DeutschFlow has no user accounts, no advertising, no analytics and no crash reporting.
          Your library, transcripts and study history are stored on your own device or in your own
          browser, not on our servers. The only outside services that receive anything are the AI
          service you connect with your own key (Groq), and the speech services of your device or
          browser.
        </p>
      </Section>

      <Section title="What stays on your device">
        <p>
          The words and phrases you save, your speech transcripts, your study history and
          statistics, your roleplay conversations and your settings. On Android this is in the
          app&apos;s private storage. On the web it is in your browser&apos;s storage (IndexedDB)
          for this site.
        </p>
        <p>
          On Android, this learning data and your settings are excluded from Google&apos;s cloud
          backup and from device-to-device transfer. A new phone starts empty unless you export a
          backup file and import it there.
        </p>
      </Section>

      <Section title="Your Groq API key">
        <p>
          AI features use your own Groq API key. It is stored encrypted on your device: under a key
          held in the Android Keystore, or under a non-extractable browser key on the web. It is sent
          only to Groq, with each request you make. You can remove it at any time in Settings.
        </p>
      </Section>

      <Section title="What is sent to Groq">
        <p>
          When you translate a transcript, look up a word or practise a roleplay, the text involved
          is sent to Groq, Inc. to produce the answer. That includes your transcript, the word, or
          your roleplay message and the recent conversation, plus your learner level if you set one.
          The request goes from your device straight to Groq under your key, and Groq&apos;s own
          terms and privacy policy apply to it. Nothing is sent to Groq unless you use one of these
          features.
        </p>
      </Section>

      <Section title="Microphone and speech">
        <p>
          The microphone is used only while you are recording, after you grant permission.
        </p>
        <p>
          <strong>Android:</strong> speech is recognised on the device. If on-device recognition is
          not available, the app says so instead of falling back to an online service.
        </p>
        <p>
          <strong>Web:</strong> speech recognition is done by your browser, which sends the audio to
          its vendor (for example Google in Chrome and Edge, or Apple in Safari) under that
          vendor&apos;s terms.
        </p>
        <p>
          Read-aloud uses your device&apos;s or browser&apos;s text-to-speech engine. The Android app
          prefers on-device voices, but the engine you have installed decides whether a voice needs
          the network.
        </p>
      </Section>

      <Section title="The website">
        <p>
          The web version is hosted on Vercel and protected by a site password. Signing in sets a
          session cookie that only proves you entered the password. To slow down password guessing,
          the server briefly records failed sign-in attempts against your IP address, and forgets
          them after 15 minutes. Like any web host, Vercel processes your IP address and request
          details to serve the site.
        </p>
      </Section>

      <Section title="Notifications and the widget">
        <p>
          The daily word notification and the home-screen widget are generated on your device from
          your own library. Nothing is sent anywhere to produce them.
        </p>
      </Section>

      <Section title="Backups you create">
        <p>
          Export creates a file on your device that you control. If you choose a password, the file
          is encrypted with it. We never receive these files.
        </p>
      </Section>

      <Section title="Deleting your data">
        <p>
          <strong>Android:</strong> Settings → &quot;Clear all progress&quot; deletes your learning
          data, and &quot;Remove saved key&quot; deletes your API key. Uninstalling the app or
          clearing its storage deletes everything.
        </p>
        <p>
          <strong>Web:</strong> Settings → &quot;Log out and erase this device&quot; deletes
          everything the site stores in your browser. A normal log-out keeps your library on the
          device.
        </p>
        <p>
          Data you sent to Groq or to a speech service is subject to their deletion policies.
        </p>
      </Section>

      <Section title="Changes">
        <p>
          If what the app stores or sends changes, this page is updated and its date changes.
        </p>
      </Section>

      <Section title="Contact">
        <p>
          {CONTACT ? (
            <>
              Questions about privacy: <a className="underline" href={`mailto:${CONTACT}`}>{CONTACT}</a>
            </>
          ) : (
            "Contact details will be added here."
          )}
        </p>
      </Section>
    </main>
  );
}
