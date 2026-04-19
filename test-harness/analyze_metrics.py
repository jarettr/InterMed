import json
import pandas as pd
import matplotlib.pyplot as plt
import argparse

def parse_duration(duration_str):
    # Парсинг ISO 8601 Duration (например "PT0.045S") в миллисекунды
    if duration_str.startswith('PT') and duration_str.endswith('S'):
        return float(duration_str[2:-1]) * 1000
    return 0.0

def main():
    parser = argparse.ArgumentParser(description='InterMed Metrics Analyzer')
    parser.add_argument('--input', type=str, default='metrics.json', help='Path to JFR JSON dump')
    args = parser.parse_args()

    with open(args.input, 'r') as f:
        data = json.load(f)

    events = data.get('recording', {}).get('events', [])

    boot_records = []
    registry_records = []

    for ev in events:
        ev_type = ev.get('type')
        vals = ev.get('values', {})
        duration_ms = parse_duration(vals.get('duration', 'PT0S'))

        if ev_type == 'org.intermed.ModBoot':
            boot_records.append({
                'Class': vals.get('className', 'Unknown'),
                'Duration (ms)': duration_ms
            })
        elif ev_type == 'org.intermed.RegistryFlush':
            registry_records.append({
                'Registry': vals.get('registryKey', 'Unknown'),
                'Objects': int(vals.get('objectCount', 0)),
                'Duration (ms)': duration_ms
            })

    # --- ТАБЛИЦЫ ---
    df_boot = pd.DataFrame(boot_records)
    print("\n=== Статистика инициализации модов (Top 10 самых долгих) ===")
    if not df_boot.empty:
        print(df_boot.sort_values(by='Duration (ms)', ascending=False).head(10).to_string(index=False))

    df_reg = pd.DataFrame(registry_records)
    print("\n=== Статистика трансляции реестров ===")
    if not df_reg.empty:
        print(df_reg.groupby('Registry').sum().sort_values(by='Duration (ms)', ascending=False).to_string())

    # --- ГРАФИКИ ---
    if not df_boot.empty:
        plt.figure(figsize=(10, 6))
        df_boot.sort_values('Duration (ms)', ascending=False).head(15).plot(
            x='Class', y='Duration (ms)', kind='bar', color='teal', legend=False
        )
        plt.title('Время загрузки Fabric Entrypoints (Топ 15)')
        plt.ylabel('Миллисекунды')
        plt.xticks(rotation=45, ha='right')
        plt.tight_layout()
        plt.savefig('mod_boot_metrics.png')
        print("\n[+] График сохранен в mod_boot_metrics.png")

if __name__ == '__main__':
    main()
