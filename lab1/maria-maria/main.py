import os
from flask import Flask, request

app = Flask(__name__)

@app.route('/health')
def health():
    return 'ok'

memory = []

@app.route('/eat')
def eat():
    mb = request.args.get('mb', default=0, type=int)
    if mb < 0:
        return "mb must be >= 0", 400
    size = mb * 1024 * 1024
    block = bytearray(size)
    memory.append(block)
    return f'{mb}'

@app.route('/burn')
def burn():
    while True:
        _ = 89174 * 832097

if __name__ == '__main__':
    port=int(os.getenv('PORT', 5000))
    app.run(host="0.0.0.0", port=port)