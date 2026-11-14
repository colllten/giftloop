/*
 * GiftLoop page behavior. Keep this small: HTMX handles requests and swaps.
 * Listeners sit on document so they keep working after HTMX swaps content.
 */

/**
 * Closes every open dropdown menu (a details element marked data-menu)
 * except the one given.
 *
 * @param {Element|null} keep menu to leave open, or null to close all
 * @returns {void}
 */
function closeMenus(keep) {
  document.querySelectorAll("details[data-menu][open]").forEach((menu) => {
    if (menu !== keep) menu.open = false;
  });
}

// Clicking anywhere outside a menu closes it.
document.addEventListener("click", (event) => {
  closeMenus(event.target.closest("details[data-menu]"));
});

// Escape closes the open menu and returns focus to its toggle.
document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape") return;
  const menu = document.querySelector("details[data-menu][open]");
  if (!menu) return;
  menu.open = false;
  menu.querySelector("summary").focus();
});
