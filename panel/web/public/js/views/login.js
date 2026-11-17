// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — ekran logowania (nick i hasło z serwera albo link z gry /panel).
import { h, icon, add } from "../dom.js";
import * as api from "../api.js";

const LINK_ERRORS = {
  link: "Link logowania jest nieważny, wygasł albo został już użyty. Wpisz /panel w grze, aby dostać nowy.",
  limit: "Za dużo prób logowania. Odczekaj kilka minut i spróbuj ponownie.",
};

/** Komunikat dla kodu z `?blad=` — tylko własne klucze (np. „constructor” nie trafia do prototypu). */
export function linkError(code) {
  return typeof code === "string" && Object.hasOwn(LINK_ERRORS, code) ? LINK_ERRORS[code] : null;
}

export function loginView(root, { query, onLogin }) {
  const errBox = h("div.alert.alert-err", { role: "alert", hidden: true });
  const showErr = (msg) => {
    errBox.textContent = msg;
    errBox.hidden = !msg;
  };
  const linkErr = linkError(query.get("blad"));
  if (linkErr) showErr(linkErr);

  const name = h("input.input#login-name", { type: "text", name: "username", autocomplete: "username", required: true, maxlength: "32", autocapitalize: "off", spellcheck: "false", placeholder: "Twój nick z gry" });
  const pass = h("input.input#login-pass", { type: "password", name: "password", autocomplete: "current-password", required: true, maxlength: "256", placeholder: "Hasło z /login" });
  const submit = h("button.btn.btn-primary.btn-block", { type: "submit" }, h("span", "Wejdź do panelu"));
  const offline = h("div.alert.alert-warn", { hidden: true }, icon("warn"), h("span", "Serwer gry jest teraz offline — logowanie będzie możliwe, gdy wróci."));

  const form = h(
    "form.login-form",
    {
      novalidate: true,
      on: {
        submit: async (e) => {
          e.preventDefault();
          showErr("");
          const n = name.value.trim();
          if (!n) return showErr("Podaj swój nick z gry."), name.focus();
          if (!pass.value) return showErr("Podaj hasło."), pass.focus();
          submit.disabled = true;
          submit.classList.add("busy");
          try {
            const me = await api.login(n, pass.value);
            pass.value = "";
            history.replaceState(null, "", "/#/");
            onLogin(me);
          } catch (err) {
            pass.value = "";
            showErr(err.message);
            if (err.status === 503) offline.hidden = false;
            pass.focus();
          } finally {
            submit.disabled = false;
            submit.classList.remove("busy");
          }
        },
      },
    },
    h("label.field", h("span.field-label", "Nick"), name),
    h("label.field", h("span.field-label", "Hasło"), pass),
    submit,
  );

  add(root, 
    h(
      "div.login-page",
      h(
        "div.login-card",
        h("img.login-crest", { src: "/favicon.svg", alt: "", width: "72", height: "72" }),
        h("h1.login-title", "MEDIVALRPG"),
        h("p.login-sub", "Panel zarządzania państwami"),
        h("div.divider", h("span", "❦")),
        offline,
        errBox,
        form,
        h("p.login-alt", icon("scroll"), h("span", "Nie pamiętasz hasła albo wolisz bez niego? ", h("span", "Wpisz "), h("code", "/panel"), h("span", " w grze — dostaniesz jednorazowy link logowania."))),
      ),
      h("footer.footer.login-footer", h("span", "© 2026 Maciej Kaczmarczyk — "), h("a", { href: "https://lektoro.pl", rel: "noopener" }, "lektoro.pl")),
    ),
  );
  api.status().then((online) => (offline.hidden = online));
  setTimeout(() => name.focus(), 30);
}
