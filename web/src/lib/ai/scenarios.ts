export interface RoleplayScenario {
  id: string;
  title: string;
  cefrLevel: string;
  context: string;
  assistantRole: string;
  learnerGoals: string[];
  openingInstruction: string;
}

export const ROLEPLAY_SCENARIOS: RoleplayScenario[] = [
  {
    id: "bakery",
    title: "Ordering at a Berlin Bakery",
    cefrLevel: "A1",
    context: "You are at a traditional bakery in Berlin ordering bread and pastries.",
    assistantRole: "A friendly baker in Berlin behind the counter.",
    learnerGoals: ["Greet the baker", "Order bread or pastries", "Ask for the total price and pay"],
    openingInstruction: "Greet the customer warmly and ask what they would like to have today.",
  },
  {
    id: "cafe",
    title: "Ordering at a Viennese Café",
    cefrLevel: "A2",
    context: "You are sitting in a traditional coffee house in Vienna.",
    assistantRole: "A polite Viennese waiter (Herr Ober).",
    learnerGoals: ["Order a Melange coffee", "Ask for a slice of cake", "Request the bill"],
    openingInstruction: "Welcome the guest to the café and ask what beverage they would like.",
  },
  {
    id: "supermarket",
    title: "Asking for Items at a Supermarket",
    cefrLevel: "A1",
    context: "You are shopping in a German supermarket trying to locate ingredients.",
    assistantRole: "A helpful supermarket employee stocking shelves.",
    learnerGoals: ["Ask where organic milk or butter is", "Ask about store opening hours"],
    openingInstruction: "Ask the customer politely if they need any assistance finding something.",
  },
  {
    id: "train_station",
    title: "Buying a Train Ticket at the Station",
    cefrLevel: "A2",
    context: "You are at the ticket counter at Frankfurt Hauptbahnhof.",
    assistantRole: "A Deutsche Bahn service agent at the ticket counter.",
    learnerGoals: ["Ask for a ticket to Munich", "Inquire about departure time and platform", "Ask if a seat reservation is included"],
    openingInstruction: "Greet the traveler and ask where they would like to travel to.",
  },
  {
    id: "buergeramt",
    title: "Registering at the Bürgeramt",
    cefrLevel: "B1",
    context: "You have an appointment at the citizen's office (Bürgeramt) for apartment registration (Anmeldung).",
    assistantRole: "A formal civil servant at the Bürgeramt.",
    learnerGoals: ["Explain you are here for Anmeldung", "Present your documents (pass, rental agreement)", "Confirm the confirmation paper"],
    openingInstruction: "Call the appointment number and ask the citizen for their purpose of visit.",
  },
  {
    id: "doctor",
    title: "Visiting a Doctor's Clinic",
    cefrLevel: "B1",
    context: "You have arrived at a general practitioner's clinic with health symptoms.",
    assistantRole: "A physician listening to your health complaints.",
    learnerGoals: ["Describe symptoms (headache, fever)", "Ask about medication and doctor's note (Attest)"],
    openingInstruction: "Greet the patient into your consultation room and ask what brings them in today.",
  },
  {
    id: "apartment",
    title: "Apartment Viewing in Munich",
    cefrLevel: "B2",
    context: "You are viewing an apartment for rent and speaking with the landlord.",
    assistantRole: "A landlord showing a 2-room apartment.",
    learnerGoals: ["Ask about warm/cold rent and utilities (Nebenkosten)", "Ask about move-in date and deposit (Kaution)"],
    openingInstruction: "Welcome the potential tenant to the apartment viewing and invite questions.",
  },
  {
    id: "job_interview",
    title: "Job Interview for a Professional Role",
    cefrLevel: "B2",
    context: "You are participating in a first-round job interview with a German employer.",
    assistantRole: "An interviewer / hiring manager from Human Resources.",
    learnerGoals: ["Introduce your professional background", "Explain your motivation for the position"],
    openingInstruction: "Thank the applicant for coming and ask them to introduce themselves briefly.",
  },
];

export const DEFAULT_SCENARIO_TITLE = ROLEPLAY_SCENARIOS[0].title;
