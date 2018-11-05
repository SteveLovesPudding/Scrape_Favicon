import requests
import csv
import threading
import time
import os

list_csv = 'top-1m.csv'
concurrent = 50
limit = 200000

success_records = []
fail_records = []
def worker(url):
    print("Checking -> %s" % url)
    r = requests.post("http://localhost:9000/get_fav", data={'url': url, 'getFresh': 'true'})
    if r.status_code == 200:
        print("Success for -> %s" % url)
        success_records.append(url)
    else:
        print("Fail for -> %s" % url)
        fail_records. append(url)

#https://stackoverflow.com/questions/312443/how-do-you-split-a-list-into-evenly-sized-chunks
def chunks(l):
    """Yield successive n-sized chunks from l."""
    for i in range(0, len(l), concurrent):
        yield l[i:i + concurrent]

threads = []

start = time.time()
with open(list_csv) as csv_file:
    csv_reader = csv.reader(csv_file, delimiter=',')
    urls = [row[1] for row in csv_reader]

success_file = "seed_success.txt"
fail_file = "seed_fail.txt"
try:
    os.remove(success_file)
except OSError:
    pass
try:
    os.remove(fail_file)
except OSError:
    pass
success = open(success_file, "a")
failure = open(fail_file, "a")
success_count = 0
chunk_count = 0

chunked_urls = chunks(urls)
for chunk in chunked_urls:
    if success_count > limit:
        break
    threads = []
    for url in chunk:
        t = threading.Thread(target=worker, args=(url,))
        threads.append(t)
        t.start()
    for t in threads:
        t.join()
    #write to file
    for r in success_records:
        success.write("%s\n" % r)
    success_count += len(success_records)
    success_records = []
    success.flush()
    for r in fail_records:
        failure.write("%s\n" % r)
    fail_records = []
    failure.flush()

    chunk_count += len(chunk)
    print("Elapsed time %d count %d" % (time.time()-start, chunk_count))

success.close()
failure.close()

end = time.time()
print("Total Elapsed time %d" % (end-start))
