// Page du dashboard : mount(element, api) où api.fetch(path) appelle /api/v1/modules/hello/<path> authentifié.
export function mount(element, api) {
  element.innerHTML = '<h2>Hello</h2><p id="hello-message">…</p>';
  api.fetch('hello').then((r) => r.json()).then((data) => {
    element.querySelector('#hello-message').textContent = data.message;
  });
}
