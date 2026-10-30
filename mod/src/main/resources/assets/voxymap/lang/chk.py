import json
zh = json.load(open(r'C:\Users\Tsutsutsu\Desktop\AgentTemp\2026-10-28-neo-voxy-1211-neoforge\mod\src\main\resources\assets\voxymap\lang\zh_cn.json', encoding='utf-8'))
en = json.load(open(r'C:\Users\Tsutsutsu\Desktop\AgentTemp\2026-10-28-neo-voxy-1211-neoforge\mod\src\main\resources\assets\voxymap\lang\en_us.json', encoding='utf-8'))
keys = [k for k in zh if ' ' in k]
print('zh keys with spaces:', len(keys))
for k in keys[:5]:
    print(' ', k, '=', zh[k][:40])
print('has blur comment:', 'Blur the water depth to smooth out sea-floor speckles' in zh)
print('en keys with spaces:', len([k for k in en if ' ' in k]))
