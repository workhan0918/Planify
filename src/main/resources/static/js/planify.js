document.addEventListener('DOMContentLoaded', () => {
document.querySelectorAll('[data-compare-target]').forEach(select => {
  const show = () => {
    const source = document.getElementById(select.value);
    const target = document.getElementById(select.dataset.compareTarget);
    if (!source || !target) return;
    const clone = source.cloneNode(true);
    clone.removeAttribute('id');
    clone.querySelectorAll('[id]').forEach(element => element.removeAttribute('id'));
    clone.querySelectorAll('details').forEach(element => element.open = true);
    target.replaceChildren(clone);
  };
  select.addEventListener('change', show); show();
});
document.querySelectorAll('[data-confirm]').forEach(form => form.addEventListener('submit', event => {
  if (!window.confirm(form.dataset.confirm)) event.preventDefault();
}));
document.querySelectorAll('input[type=file]').forEach(input => input.addEventListener('change', () => {
  const file = input.files[0];
  input.setCustomValidity(file && file.size === 0 ? '선택한 파일이 0바이트입니다. 내용을 저장한 뒤 다시 제출해주세요.' :
    file && file.size > 20 * 1024 * 1024 ? '파일은 최대 20MB입니다.' : '');
  input.reportValidity();
}));
if (window.Chart) {
  document.querySelectorAll('[data-chart]').forEach(canvas => {
    const labels = JSON.parse(canvas.dataset.labels);
    const values = JSON.parse(canvas.dataset.values);
    new Chart(canvas, {type: canvas.dataset.chart, data: {labels,
      datasets: [{label: canvas.dataset.label || '과제 수', data: values,
        backgroundColor: canvas.dataset.chart === 'doughnut' ? ['#4e73df','#e5eaf5'] : '#4e73df', borderRadius: 6}]},
      options: {responsive: true, maintainAspectRatio: false,
        plugins: {legend: {display: canvas.dataset.chart === 'doughnut'}},
        scales: canvas.dataset.chart === 'doughnut' ? {} : {y: {beginAtZero: true, max: canvas.dataset.percent ? 100 : undefined}}}});
  });
}
});
